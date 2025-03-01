package bloop

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.Executor

import scala.collection.mutable
import scala.concurrent.Promise
import scala.util.control.NonFatal

import bloop.io.AbsolutePath
import bloop.io.ParallelOps
import bloop.io.ParallelOps.CopyMode
import bloop.io.{Paths => BloopPaths}
import bloop.logging.DebugFilter
import bloop.logging.Logger
import bloop.logging.ObservedLogger
import bloop.reporter.ProblemPerPhase
import bloop.reporter.Reporter
import bloop.reporter.ZincReporter
import bloop.task.Task
import bloop.tracing.BraveTracer
import bloop.util.AnalysisUtils
import bloop.util.CacheHashCode
import bloop.util.JavaRuntime
import bloop.util.UUIDUtil

import monix.execution.Scheduler
import sbt.internal.inc.Analysis
import sbt.internal.inc.ConcreteAnalysisContents
import sbt.internal.inc.FileAnalysisStore
import sbt.internal.inc.FreshCompilerCache
import sbt.internal.inc.PlainVirtualFileConverter
import sbt.internal.inc.bloop.BloopZincCompiler
import sbt.internal.inc.bloop.internal.BloopLookup
import sbt.internal.inc.bloop.internal.BloopStamps
import sbt.util.InterfaceUtil
import xsbti.T2
import xsbti.VirtualFileRef
import xsbti.compile._

case class CompileInputs(
    scalaInstance: ScalaInstance,
    compilerCache: CompilerCache,
    sources: Array[AbsolutePath],
    classpath: Array[AbsolutePath],
    uniqueInputs: UniqueCompileInputs,
    out: CompileOutPaths,
    baseDirectory: AbsolutePath,
    scalacOptions: Array[String],
    javacOptions: Array[String],
    javacBin: Option[AbsolutePath],
    compileOrder: CompileOrder,
    classpathOptions: ClasspathOptions,
    previousResult: PreviousResult,
    previousCompilerResult: Compiler.Result,
    reporter: ZincReporter,
    logger: ObservedLogger[Logger],
    dependentResults: Map[File, PreviousResult],
    cancelPromise: Promise[Unit],
    tracer: BraveTracer,
    ioScheduler: Scheduler,
    ioExecutor: Executor,
    invalidatedClassFilesInDependentProjects: Set[File],
    generatedClassFilePathsInDependentProjects: Map[String, File]
)

case class CompileOutPaths(
    out: AbsolutePath,
    analysisOut: AbsolutePath,
    genericClassesDir: AbsolutePath,
    externalClassesDir: AbsolutePath,
    internalReadOnlyClassesDir: AbsolutePath
) {
  // Don't change the internals of this method without updating how they are cleaned up
  private def createInternalNewDir(generateDirName: String => String): AbsolutePath = {
    val parentInternalDir = CompileOutPaths.createInternalClassesRootDir(out).underlying

    /*
     * Use external classes dir as the beginning of the new internal directory name.
     * This is done for two main reasons:
     *   1. To easily know which internal directory was triggered by a
     *      compilation of another client.
     *   2. To ease cleanup of orphan directories (those directories that were
     *      not removed by the server because, for example, an unexpected SIGKILL)
     *      in the middle of compilation.
     */
    val classesName = externalClassesDir.underlying.getFileName()
    val newClassesName = generateDirName(classesName.toString)
    AbsolutePath(Files.createDirectories(parentInternalDir.resolve(newClassesName)).toRealPath())
  }

  lazy val internalNewClassesDir: AbsolutePath = {
    createInternalNewDir(classesName => s"${classesName}-${UUIDUtil.randomUUID}")
  }

  /**
   * Creates an internal directory where symbol pickles are stored when build
   * pipelining is enabled. This directory is removed whenever compilation
   * process has finished because pickles are useless when class files are
   * present. This might change when we expose pipelining to clients.
   *
   * Watch out: for the moment this pickles dir is not used anywhere.
   */
  lazy val internalNewPicklesDir: AbsolutePath = {
    createInternalNewDir { classesName =>
      val newName = s"${classesName.replace("classes", "pickles")}-${UUIDUtil.randomUUID}"
      // If original classes name didn't contain `classes`, add pickles at the beginning
      if (newName.contains("pickles")) newName
      else "pickles-" + newName
    }
  }
}

object CompileOutPaths {

  /**
   * Defines a project-specific root directory where all the internal classes
   * directories used for compilation are created.
   */
  def createInternalClassesRootDir(out: AbsolutePath): AbsolutePath = {
    AbsolutePath(
      Files.createDirectories(
        out.resolve("bloop-internal-classes").underlying
      )
    )
  }

  /*
   * An empty classes directory never exists on purpose. It is merely a
   * placeholder until a non empty classes directory is used. There is only
   * one single empty classes directory per project and can be shared by
   * different projects, so to avoid problems across different compilations
   * we never create this directory and special case Zinc logic to skip it.
   *
   * The prefix name 'classes-empty-` of this classes directory should not
   * change without modifying `BloopLookup` defined in `backend`.
   */
  def deriveEmptyClassesDir(projectName: String, genericClassesDir: AbsolutePath): AbsolutePath = {
    val classesDirName = s"classes-empty-${projectName}"
    genericClassesDir.getParent.resolve(classesDirName)
  }

  private val ClassesEmptyDirPrefix = java.io.File.separator + "classes-empty-"
  def hasEmptyClassesDir(classesDir: AbsolutePath): Boolean = {
    /*
     * Empty classes dirs don't exist so match on path.
     *
     * Don't match on `getFileName` because `classes-empty` is followed by
     * target name, which could contains `java.io.File.separator`, making
     * `getFileName` pick the suffix after the latest separator.
     *
     * e.g. if target name is
     * `util/util-function/src/main/java/com/twitter/function:function`
     * classes empty dir path will be
     * `classes-empty-util/util-function/src/main/java/com/twitter/function:function`.
     * and `getFileName` would yield `function:function` which is not what we want.
     * Hence we avoid using this code for the implementation:
     * `classesDir.underlying.getFileName().toString.startsWith("classes-empty-")`
     */
    classesDir.syntax.contains(ClassesEmptyDirPrefix)
  }
}

object Compiler {
  private implicit val filter: DebugFilter.Compilation.type = bloop.logging.DebugFilter.Compilation
  private val converter = PlainVirtualFileConverter.converter
  private final class BloopProgress(
      reporter: ZincReporter,
      cancelPromise: Promise[Unit]
  ) extends CompileProgress {
    override def startUnit(phase: String, unitPath: String): Unit = {
      reporter.reportNextPhase(phase, new java.io.File(unitPath))
    }

    override def advance(
        current: Int,
        total: Int,
        prevPhase: String,
        nextPhase: String
    ): Boolean = {
      val isNotCancelled = !cancelPromise.isCompleted
      if (isNotCancelled) {
        reporter.reportCompilationProgress(current.toLong, total.toLong)
      }

      isNotCancelled
    }
  }

  sealed trait Result
  object Result {
    final case object Empty extends Result with CacheHashCode
    final case class Blocked(on: List[String]) extends Result with CacheHashCode
    final case class GlobalError(problem: String, err: Option[Throwable])
        extends Result
        with CacheHashCode

    final case class Success(
        inputs: UniqueCompileInputs,
        reporter: ZincReporter,
        products: CompileProducts,
        elapsed: Long,
        backgroundTasks: CompileBackgroundTasks,
        isNoOp: Boolean,
        reportedFatalWarnings: Boolean
    ) extends Result
        with CacheHashCode

    final case class Failed(
        problems: List[ProblemPerPhase],
        t: Option[Throwable],
        elapsed: Long,
        backgroundTasks: CompileBackgroundTasks
    ) extends Result
        with CacheHashCode

    final case class Cancelled(
        problems: List[ProblemPerPhase],
        elapsed: Long,
        backgroundTasks: CompileBackgroundTasks
    ) extends Result
        with CacheHashCode

    object Ok {
      def unapply(result: Result): Option[Result] = result match {
        case s @ (Success(_, _, _, _, _, _, _) | Empty) => Some(s)
        case _ => None
      }
    }

    object NotOk {
      def unapply(result: Result): Option[Result] = result match {
        case f @ (Failed(_, _, _, _) | Cancelled(_, _, _) | Blocked(_) | GlobalError(_, _)) =>
          Some(f)
        case _ => None
      }
    }
  }

  def compile(compileInputs: CompileInputs): Task[Result] = {
    val logger = compileInputs.logger
    val tracer = compileInputs.tracer
    val compileOut = compileInputs.out
    val cancelPromise = compileInputs.cancelPromise
    val externalClassesDir = compileOut.externalClassesDir.underlying
    val externalClassesDirPath = externalClassesDir.toString
    val readOnlyClassesDir = compileOut.internalReadOnlyClassesDir.underlying
    val readOnlyClassesDirPath = readOnlyClassesDir.toString
    val newClassesDir = compileOut.internalNewClassesDir.underlying
    val newClassesDirPath = newClassesDir.toString

    logger.debug(s"External classes directory ${externalClassesDirPath}")
    logger.debug(s"Read-only classes directory ${readOnlyClassesDirPath}")
    logger.debug(s"New rw classes directory ${newClassesDirPath}")

    val allGeneratedRelativeClassFilePaths = new mutable.HashMap[String, File]()
    val readOnlyCopyDenylist = new mutable.HashSet[Path]()
    val allInvalidatedClassFilesForProject = new mutable.HashSet[File]()
    val allInvalidatedExtraCompileProducts = new mutable.HashSet[File]()

    val backgroundTasksWhenNewSuccessfulAnalysis =
      new mutable.ListBuffer[CompileBackgroundTasks.Sig]()
    val backgroundTasksForFailedCompilation =
      new mutable.ListBuffer[CompileBackgroundTasks.Sig]()

    def newFileManager: ClassFileManager = {
      new BloopClassFileManager(
        Files.createTempDirectory("bloop"),
        compileInputs,
        compileOut,
        allGeneratedRelativeClassFilePaths,
        readOnlyCopyDenylist,
        allInvalidatedClassFilesForProject,
        allInvalidatedExtraCompileProducts,
        backgroundTasksWhenNewSuccessfulAnalysis,
        backgroundTasksForFailedCompilation
      )
    }

    val isFatalWarningsEnabled: Boolean =
      compileInputs.scalacOptions.exists(_ == "-Xfatal-warnings")
    def getInputs(compilers: Compilers): Inputs = {
      val options = getCompilationOptions(compileInputs, logger, newClassesDir)
      val setup = getSetup(compileInputs)
      Inputs.of(compilers, options, setup, compileInputs.previousResult)
    }

    def getSetup(compileInputs: CompileInputs): Setup = {
      val skip = false
      val empty = Array.empty[T2[String, String]]
      val results = compileInputs.dependentResults.+(
        readOnlyClassesDir.toFile -> compileInputs.previousResult,
        newClassesDir.toFile -> compileInputs.previousResult
      )

      val lookup = new BloopClasspathEntryLookup(
        results,
        compileInputs.uniqueInputs.classpath,
        converter
      )
      val reporter = compileInputs.reporter
      val compilerCache = new FreshCompilerCache
      val cacheFile = compileInputs.baseDirectory.resolve("cache").toFile
      val incOptions = {
        val disableIncremental = java.lang.Boolean.getBoolean("bloop.zinc.disabled")
        // Don't customize class file manager bc we pass our own to the zinc APIs directly
        IncOptions.create().withEnabled(!disableIncremental)
      }

      val progress = Optional.of[CompileProgress](new BloopProgress(reporter, cancelPromise))
      Setup.create(lookup, skip, cacheFile, compilerCache, incOptions, reporter, progress, empty)
    }

    val start = System.nanoTime()
    val scalaInstance = compileInputs.scalaInstance
    val classpathOptions = compileInputs.classpathOptions
    val compilers = compileInputs.compilerCache.get(
      scalaInstance,
      compileInputs.javacBin,
      compileInputs.javacOptions.toList
    )
    val inputs = tracer.traceVerbose("creating zinc inputs")(_ => getInputs(compilers))

    // We don't need nanosecond granularity, we're happy with milliseconds
    def elapsed: Long = ((System.nanoTime() - start).toDouble / 1e6).toLong

    import ch.epfl.scala.bsp
    import scala.util.{Success, Failure}
    val reporter = compileInputs.reporter

    def cancel(): Unit = {
      // Complete all pending promises when compilation is cancelled
      logger.debug(s"Cancelling compilation from ${readOnlyClassesDirPath} to ${newClassesDirPath}")
      compileInputs.cancelPromise.trySuccess(())

      // Always report the compilation of a project no matter if it's completed
      reporter.reportCancelledCompilation()
    }

    val previousAnalysis = InterfaceUtil.toOption(compileInputs.previousResult.analysis())
    val previousSuccessfulProblems = previousProblemsFromSuccessfulCompilation(previousAnalysis)
    val previousProblems =
      previousProblemsFromResult(compileInputs.previousCompilerResult, previousSuccessfulProblems)

    def handleCancellation: Compiler.Result = {
      val cancelledCode = bsp.StatusCode.Cancelled
      reporter.processEndCompilation(previousSuccessfulProblems, cancelledCode, None, None)
      reporter.reportEndCompilation()
      val backgroundTasks =
        toBackgroundTasks(backgroundTasksForFailedCompilation.toList)
      Result.Cancelled(reporter.allProblemsPerPhase.toList, elapsed, backgroundTasks)
    }

    val uniqueInputs = compileInputs.uniqueInputs
    reporter.reportStartCompilation(previousProblems)
    BloopZincCompiler
      .compile(
        inputs,
        reporter,
        logger,
        uniqueInputs,
        newFileManager,
        cancelPromise,
        tracer,
        classpathOptions
      )
      .materialize
      .doOnCancel(Task(cancel()))
      .map {
        case Success(_) if cancelPromise.isCompleted => handleCancellation
        case Success(result) =>
          // Report end of compilation only after we have reported all warnings from previous runs
          val sourcesWithFatal = reporter.getSourceFilesWithFatalWarnings
          val reportedFatalWarnings = isFatalWarningsEnabled && sourcesWithFatal.nonEmpty
          val code = if (reportedFatalWarnings) bsp.StatusCode.Error else bsp.StatusCode.Ok

          // Process the end of compilation, but wait for reporting until client tasks run
          reporter.processEndCompilation(
            previousSuccessfulProblems,
            code,
            Some(compileOut.externalClassesDir),
            Some(compileOut.analysisOut)
          )

          // Compute the results we should use for dependent compilations and new compilation runs
          val resultForDependentCompilationsInSameRun =
            PreviousResult.of(Optional.of(result.analysis()), Optional.of(result.setup()))
          val analysis = result.analysis()

          def updateExternalClassesDirWithReadOnly(
              clientClassesDir: AbsolutePath,
              clientTracer: BraveTracer,
              clientLogger: Logger
          ): Task[Unit] = Task.defer {
            val descriptionMsg = s"Updating external classes dir with read only $clientClassesDir"
            clientTracer.traceTaskVerbose(descriptionMsg) { _ =>
              Task.defer {
                clientLogger.debug(descriptionMsg)
                val invalidatedClassFiles =
                  allInvalidatedClassFilesForProject.iterator.map(_.toPath).toSet
                val invalidatedExtraProducts =
                  allInvalidatedExtraCompileProducts.iterator.map(_.toPath).toSet
                val invalidatedInThisProject = invalidatedClassFiles ++ invalidatedExtraProducts
                val denyList = invalidatedInThisProject ++ readOnlyCopyDenylist.iterator
                val config =
                  ParallelOps.CopyConfiguration(5, CopyMode.ReplaceIfMetadataMismatch, denyList)
                val lastCopy = ParallelOps.copyDirectories(config)(
                  readOnlyClassesDir,
                  clientClassesDir.underlying,
                  compileInputs.ioScheduler,
                  enableCancellation = false,
                  compileInputs.logger
                )

                lastCopy.map { _ =>
                  clientLogger.debug(
                    s"Finished copying classes from $readOnlyClassesDir to $clientClassesDir"
                  )
                  ()
                }
              }
            }
          }

          def persistAnalysis(analysis: CompileAnalysis, out: AbsolutePath): Task[Unit] = {
            // Important to memoize it, it's triggered by different clients
            Task(persist(out, analysis, result.setup, tracer, logger)).memoize
          }

          val isNoOp = previousAnalysis.contains(analysis)
          if (isNoOp) {
            // If no-op, return previous result with updated classpath hashes
            val noOpPreviousResult = {
              updatePreviousResultWithRecentClasspathHashes(
                compileInputs.previousResult,
                uniqueInputs
              )
            }

            val products = CompileProducts(
              readOnlyClassesDir,
              readOnlyClassesDir,
              noOpPreviousResult,
              noOpPreviousResult,
              Set(),
              Map.empty
            )

            val backgroundTasks = new CompileBackgroundTasks {
              def trigger(
                  clientClassesDir: AbsolutePath,
                  clientReporter: Reporter,
                  clientTracer: BraveTracer,
                  clientLogger: Logger
              ): Task[Unit] = Task.defer {
                clientLogger.debug(s"Triggering background tasks for $clientClassesDir")
                val updateClientState =
                  updateExternalClassesDirWithReadOnly(clientClassesDir, clientTracer, clientLogger)

                val writeAnalysisIfMissing = {
                  if (compileOut.analysisOut.exists) Task.unit
                  else {
                    previousAnalysis match {
                      case None => Task.unit
                      case Some(analysis) => persistAnalysis(analysis, compileOut.analysisOut)
                    }
                  }
                }

                val deleteNewClassesDir = Task(BloopPaths.delete(AbsolutePath(newClassesDir)))
                val allTasks = List(deleteNewClassesDir, updateClientState, writeAnalysisIfMissing)
                Task
                  .gatherUnordered(allTasks)
                  .map(_ => ())
                  .onErrorHandleWith(err => {
                    clientLogger.debug("Caught error in background tasks"); clientLogger.trace(err);
                    Task.raiseError(err)
                  })
                  .doOnFinish(_ => Task(clientReporter.reportEndCompilation()))
              }
            }

            Result.Success(
              compileInputs.uniqueInputs,
              compileInputs.reporter,
              products,
              elapsed,
              backgroundTasks,
              isNoOp,
              reportedFatalWarnings
            )
          } else {
            val allGeneratedProducts = allGeneratedRelativeClassFilePaths.toMap
            val analysisForFutureCompilationRuns = {
              rebaseAnalysisClassFiles(
                analysis,
                readOnlyClassesDir,
                newClassesDir,
                sourcesWithFatal
              )
            }

            val resultForFutureCompilationRuns = {
              resultForDependentCompilationsInSameRun.withAnalysis(
                Optional.of(analysisForFutureCompilationRuns): Optional[CompileAnalysis]
              )
            }

            // Delete all those class files that were invalidated in the external classes dir
            val allInvalidated =
              allInvalidatedClassFilesForProject ++ allInvalidatedExtraCompileProducts

            // Schedule the tasks to run concurrently after the compilation end
            val backgroundTasksExecution = new CompileBackgroundTasks {
              def trigger(
                  clientClassesDir: AbsolutePath,
                  clientReporter: Reporter,
                  clientTracer: BraveTracer,
                  clientLogger: Logger
              ): Task[Unit] = {
                val clientClassesDirPath = clientClassesDir.toString
                val successBackgroundTasks =
                  backgroundTasksWhenNewSuccessfulAnalysis
                    .map(f => f(clientClassesDir, clientReporter, clientTracer))
                val persistTask =
                  persistAnalysis(analysisForFutureCompilationRuns, compileOut.analysisOut)
                val initialTasks = persistTask :: successBackgroundTasks.toList
                val allClientSyncTasks = Task.gatherUnordered(initialTasks).flatMap { _ =>
                  // Only start these tasks after the previous IO tasks in the external dir are done
                  val firstTask = updateExternalClassesDirWithReadOnly(
                    clientClassesDir,
                    clientTracer,
                    clientLogger
                  )

                  val secondTask = Task {
                    allInvalidated.foreach { f =>
                      val path = AbsolutePath(f.toPath)
                      val syntax = path.syntax
                      if (syntax.startsWith(readOnlyClassesDirPath)) {
                        val rebasedFile = AbsolutePath(
                          syntax.replace(readOnlyClassesDirPath, clientClassesDirPath)
                        )
                        if (rebasedFile.exists) {
                          Files.delete(rebasedFile.underlying)
                        }
                      }
                    }
                  }
                  Task.gatherUnordered(List(firstTask, secondTask)).map(_ => ())
                }

                allClientSyncTasks.doOnFinish(_ => Task(clientReporter.reportEndCompilation()))
              }
            }

            val products = CompileProducts(
              readOnlyClassesDir,
              newClassesDir,
              resultForDependentCompilationsInSameRun,
              resultForFutureCompilationRuns,
              allInvalidated.toSet,
              allGeneratedProducts
            )

            Result.Success(
              compileInputs.uniqueInputs,
              compileInputs.reporter,
              products,
              elapsed,
              backgroundTasksExecution,
              isNoOp,
              reportedFatalWarnings
            )
          }
        case Failure(_: xsbti.CompileCancelled) => handleCancellation
        case Failure(cause) =>
          val errorCode = bsp.StatusCode.Error
          reporter.processEndCompilation(previousSuccessfulProblems, errorCode, None, None)
          reporter.reportEndCompilation()

          cause match {
            case f: xsbti.CompileFailed =>
              // We cannot guarantee reporter.problems == f.problems, so we aggregate them together
              val reportedProblems = reporter.allProblemsPerPhase.toList
              val rawProblemsFromReporter = reportedProblems.iterator.map(_.problem).toSet
              val newProblems = f.problems().flatMap { p =>
                if (rawProblemsFromReporter.contains(p)) Nil
                else List(ProblemPerPhase(p, None))
              }
              val failedProblems = reportedProblems ++ newProblems.toList
              val backgroundTasks =
                toBackgroundTasks(backgroundTasksForFailedCompilation.toList)
              Result.Failed(failedProblems, None, elapsed, backgroundTasks)
            case t: Throwable =>
              t.printStackTrace()
              val backgroundTasks =
                toBackgroundTasks(backgroundTasksForFailedCompilation.toList)
              Result.Failed(Nil, Some(t), elapsed, backgroundTasks)
          }
      }
  }

  /**
   * Bloop runs Scala compilation in the same process as the main server,
   * so the compilation process will use the same JDK that Bloop is using.
   * That's why we must ensure that produce class files will be compliant with expected JDK version
   * and compilation errors will show up when using wrong JDK API.
   */
  private def adjustScalacReleaseOptions(
      scalacOptions: Array[String],
      javacBin: Option[AbsolutePath],
      logger: Logger
  ): Array[String] = {
    def existsReleaseSetting = scalacOptions.exists(opt =>
      opt.startsWith("-release") ||
        opt.startsWith("--release") ||
        opt.startsWith("-java-output-version")
    )
    def sameHome = javacBin match {
      case Some(bin) => bin.getParent.getParent == JavaRuntime.home
      case None => false
    }

    javacBin.flatMap(binary =>
      // <JAVA_HOME>/bin/java
      JavaRuntime.getJavaVersionFromJavaHome(binary.getParent.getParent)
    ) match {
      case None => scalacOptions
      case Some(_) if existsReleaseSetting || sameHome => scalacOptions
      case Some(version) =>
        try {
          val numVer = if (version.startsWith("1.8")) 8 else version.takeWhile(_.isDigit).toInt
          val bloopNumVer = JavaRuntime.version.takeWhile(_.isDigit).toInt
          if (bloopNumVer > numVer) {
            scalacOptions ++ List("-release", numVer.toString())
          } else if (bloopNumVer < numVer) {
            logger.warn(
              s"Bloop is running with ${JavaRuntime.version} but your code requires $version to compile, " +
                "this might cause some compilation issues when using JDK API unsupported by the Bloop's current JVM version"
            )
            scalacOptions
          } else {
            scalacOptions
          }
        } catch {
          case NonFatal(_) =>
            scalacOptions
        }
    }
  }

  private def getCompilationOptions(
      inputs: CompileInputs,
      logger: Logger,
      newClassesDir: Path
  ): CompileOptions = {
    // Sources are all files
    val sources = inputs.sources.map(path => converter.toVirtualFile(path.underlying))
    val classpath = inputs.classpath.map(path => converter.toVirtualFile(path.underlying))

    val scalacOptions = adjustScalacReleaseOptions(
      scalacOptions = inputs.scalacOptions,
      javacBin = inputs.javacBin,
      logger = logger
    )

    val optionsWithoutFatalWarnings = scalacOptions.filter(_ != "-Xfatal-warnings")
    val areFatalWarningsEnabled = scalacOptions.length != optionsWithoutFatalWarnings.length

    // Enable fatal warnings in the reporter if they are enabled in the build
    if (areFatalWarningsEnabled)
      inputs.reporter.enableFatalWarnings()

    CompileOptions
      .create()
      .withClassesDirectory(newClassesDir)
      .withSources(sources)
      .withClasspath(classpath)
      .withScalacOptions(optionsWithoutFatalWarnings)
      .withJavacOptions(inputs.javacOptions)
      .withOrder(inputs.compileOrder)
  }

  def toBackgroundTasks(
      tasks: List[(AbsolutePath, Reporter, BraveTracer) => Task[Unit]]
  ): CompileBackgroundTasks = {
    new CompileBackgroundTasks {
      def trigger(
          clientClassesDir: AbsolutePath,
          clientReporter: Reporter,
          tracer: BraveTracer,
          clientLogger: Logger
      ): Task[Unit] = {
        val backgroundTasks = tasks.map(f => f(clientClassesDir, clientReporter, tracer))
        Task.gatherUnordered(backgroundTasks).memoize.map(_ => ())
      }
    }
  }

  def analysisFrom(prev: PreviousResult): Option[CompileAnalysis] = {
    InterfaceUtil.toOption(prev.analysis())
  }

  /**
   * Returns the problems (infos/warnings) that were generated in the last
   * successful incremental compilation. These problems are material for
   * the correct handling of compiler reporters since they might be stateful
   * with the clients (e.g. BSP reporter).
   */
  def previousProblemsFromSuccessfulCompilation(
      analysis: Option[CompileAnalysis]
  ): List[ProblemPerPhase] = {
    analysis.map(prev => AnalysisUtils.problemsFrom(prev)).getOrElse(Nil)
  }

  /**
   * the problems (errors/warnings/infos) that were generated in the
   * last incremental compilation, be it successful or not. See
   * [[previousProblemsFromSuccessfulCompilation]] for an explanation of why
   * these problems are important for the bloop compilation.
   *
   * @see [[previousProblemsFromSuccessfulCompilation]]
   */
  def previousProblemsFromResult(
      result: Compiler.Result,
      previousSuccessfulProblems: List[ProblemPerPhase]
  ): List[ProblemPerPhase] = {
    result match {
      case f: Compiler.Result.Failed => f.problems
      case c: Compiler.Result.Cancelled => c.problems
      case _: Compiler.Result.Success => previousSuccessfulProblems
      case _ => Nil
    }
  }

  /**
   * Update the previous result with the most recent classpath hashes.
   *
   * The incremental compiler has two mechanisms to ascertain if it has to
   * recompile code or can skip recompilation (what it's traditionally called
   * as a no-op compile). The first phase checks if classpath hashes are the
   * same. If they are, it's a no-op compile, if they are not then it passes to
   * the second phase which does an expensive classpath computation to better
   * decide if a recompilation is needed.
   *
   * This last step can be expensive when there are lots of projects in a
   * build and even more so when these projects produce no-op compiles. This
   * method makes sure we update the classpath hash if Zinc finds a change in
   * the classpath and still decides it's a no-op compile. This prevents
   * subsequent no-op compiles from paying the price for the same expensive
   * classpath check.
   */
  def updatePreviousResultWithRecentClasspathHashes(
      previous: PreviousResult,
      uniqueInputs: UniqueCompileInputs
  ): PreviousResult = {
    val newClasspathHashes =
      BloopLookup.filterOutDirsFromHashedClasspath(uniqueInputs.classpath)
    val newSetup = InterfaceUtil
      .toOption(previous.setup())
      .map(s => s.withOptions(s.options().withClasspathHash(newClasspathHashes.toArray)))
    previous.withSetup(InterfaceUtil.toOptional(newSetup))
  }

  /**
   * Change the paths of the class files inside the analysis.
   *
   * As compiler isolation requires every process to write to an independent
   * classes directory, while still sourcing the previous class files from a
   * read-only classes directory, this method has to ensure that the next
   * user of this analysis sees that all products come from the same directory,
   * which is the new classes directory we've written to.
   *
   * Up in the bloop call stack, we make sure that we spawn a process that
   * copies all class files from the read-only classes directory to the new
   * classes directory so that the new paths in the analysis exist in the file
   * system.
   */
  def rebaseAnalysisClassFiles(
      analysis0: CompileAnalysis,
      readOnlyClassesDir: Path,
      newClassesDir: Path,
      sourceFilesWithFatalWarnings: scala.collection.Set[File]
  ): Analysis = {
    // Cast to the only internal analysis that we support
    val analysis = analysis0.asInstanceOf[Analysis]
    def rebase(file: VirtualFileRef): VirtualFileRef = {

      val filePath = converter.toPath(file).toAbsolutePath()
      if (!filePath.startsWith(readOnlyClassesDir)) file
      else {
        // Hash for class file is the same because the copy duplicates metadata
        val path = newClassesDir.resolve(readOnlyClassesDir.relativize(filePath))
        converter.toVirtualFile(path)
      }
    }

    val newStamps = {
      import sbt.internal.inc.Stamps
      val oldStamps = analysis.stamps
      // Use empty stamps for files that have fatal warnings so that next compile recompiles them
      val rebasedSources = oldStamps.sources.map {
        case t @ (virtualFile, _) =>
          val file = converter.toPath(virtualFile).toFile()
          // Assumes file in reported diagnostic matches path in here
          val fileHasFatalWarnings = sourceFilesWithFatalWarnings.contains(file)
          if (!fileHasFatalWarnings) t
          else virtualFile -> BloopStamps.emptyStamps
      }
      val rebasedProducts = oldStamps.products.map {
        case t @ (file, _) =>
          val rebased = rebase(file)
          if (rebased == file) t else rebased -> t._2
      }
      // Changes the paths associated with the class file paths
      Stamps(rebasedProducts, rebasedSources, oldStamps.libraries)
    }

    val newRelations = {
      import sbt.internal.inc.bloop.ZincInternals
      val oldRelations = analysis.relations
      // Changes the source to class files relation
      ZincInternals.copyRelations(oldRelations, rebase(_))
    }

    analysis.copy(stamps = newStamps, relations = newRelations)
  }

  def persist(
      storeFile: AbsolutePath,
      analysis: CompileAnalysis,
      setup: MiniSetup,
      tracer: BraveTracer,
      logger: Logger
  ): Unit = {
    val label = s"Writing analysis to ${storeFile.syntax}..."
    tracer.trace(label) { _ =>
      if (analysis == Analysis.Empty || analysis.equals(Analysis.empty)) {
        logger.debug(s"Skipping analysis persistence to ${storeFile.syntax}, analysis is empty")
      } else {
        logger.debug(label)
        FileAnalysisStore.binary(storeFile.toFile).set(ConcreteAnalysisContents(analysis, setup))
        logger.debug(s"Wrote analysis to ${storeFile.syntax}...")
      }
    }
  }
}

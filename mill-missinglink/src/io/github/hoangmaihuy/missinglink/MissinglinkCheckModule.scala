package io.github.hoangmaihuy.missinglink

import java.io.{File, FileInputStream}
import scala.jdk.CollectionConverters.*

import com.spotify.missinglink.Conflict.ConflictCategory
import com.spotify.missinglink.datamodel.{Dependency, *}
import com.spotify.missinglink.{ArtifactLoader, Conflict, ConflictChecker, Java9ModuleLoader}
import mill.*
import mill.api.*
import mill.scalalib.*

trait MissinglinkCheckModule extends JavaModule {

  /** Fail the build if any conflicts are found */
  def missinglinkFailOnConflicts: T[Boolean] = Task { true }

  /** Also scan all dependencies */
  def missinglinkScanDependencies: T[Boolean] = Task { false }

  /** Optional list of packages to ignore conflicts where the source of the conflict is in one of the specified
    * packages.
    */
  def missinglinkIgnoreSourcePackages: T[Seq[IgnoredPackage]] = Task { Seq.empty[IgnoredPackage] }

  /** Optional list of source packages to specifically target conflicts in. Cannot be used with
    * missinglinkIgnoreSourcePackages.
    */
  def missinglinkTargetSourcePackages: T[Seq[TargetedPackage]] = Task { Seq.empty[TargetedPackage] }

  /** Optional list of packages to ignore conflicts where the destination/called-side of the conflict is in one of the
    * specified packages.
    */
  def missinglinkIgnoreDestinationPackages: T[Seq[IgnoredPackage]] = Task { Seq.empty[IgnoredPackage] }

  /** Optional list of source packages to specifically target conflicts in. Cannot be used with
    * missinglinkIgnoreDestinationPackages.
    */
  def missinglinkTargetDestinationPackages: T[Seq[TargetedPackage]] = Task { Seq.empty[TargetedPackage] }

  /** Dependencies that are excluded from analysis */
  def missinglinkExcludedDependencies: T[Seq[DependencyFilter]] = Task { Seq.empty[DependencyFilter] }

  def missinglinkCheckCached = Task {
    missinglinkCheck()()
  }

  def missinglinkCheck(): Command[Unit] = Task.Command {
    assert(
      missinglinkIgnoreSourcePackages().isEmpty || missinglinkTargetSourcePackages().isEmpty,
      "ignoreSourcePackages and targetSourcePackages cannot be defined in the same project."
    )

    assert(
      missinglinkIgnoreDestinationPackages().isEmpty || missinglinkTargetDestinationPackages().isEmpty,
      "ignoreDestinationPackages and targetDestinationPackages cannot be defined in the same project."
    )

    val classDirectory = compile().classes.path
    val runCp = runClasspath().map(_.path).distinct

    val conflicts =
      loadArtifactsAndCheckConflicts(
        runCp,
        classDirectory,
        missinglinkScanDependencies(),
        missinglinkExcludedDependencies(),
        Task.log
      )

    val conflictFilters = filterConflicts(
      missinglinkIgnoreSourcePackages(),
      "missinglinkIgnoreSourcePackages",
      Task.log,
      "source",
      _.fromClass
    ) andThen filterConflicts(
      missinglinkTargetSourcePackages(),
      "missinglinkTargetSourcePackages",
      Task.log,
      "source",
      _.fromClass
    ) andThen filterConflicts(
      missinglinkIgnoreDestinationPackages(),
      "missinglinkIgnoreDestinationPackages",
      Task.log,
      "destination",
      _.targetClass
    ) andThen filterConflicts(
      missinglinkTargetDestinationPackages(),
      "missinglinkTargetDestinationPackages",
      Task.log,
      "destination",
      _.targetClass
    )

    val filteredConflicts = conflictFilters(conflicts)

    if (filteredConflicts.nonEmpty) {
      val initialTotal = conflicts.length
      val filteredTotal = filteredConflicts.length

      val diffMessage = if (initialTotal != filteredTotal) {
        s"($initialTotal conflicts were found before applying filters)"
      } else {
        ""
      }

      Task.log.info(s"$filteredTotal conflicts found! $diffMessage")

      outputConflicts(filteredConflicts, Task.log)

      if (missinglinkFailOnConflicts()) {
        throw new Exception(s"There were $filteredTotal conflicts")
      }
    } else {
      Task.log.info("No conflicts found")
    }
  }

  private def loadArtifactsAndCheckConflicts(
    cp: Seq[os.Path],
    classDirectory: os.Path,
    scanDependencies: Boolean,
    excludedDependencies: Seq[DependencyFilter],
    log: Logger
  ): Seq[Conflict] = {

    val runtimeArtifacts = constructArtifacts(cp, log)

    val runtimeArtifactsAfterExclusions = runtimeArtifacts
      .filterNot { artifact =>
        artifact.path.fold(true) { path =>
          excludedDependencies.exists(_.check(path))
        }
      }
      .map(_.artifact)

    // also need to load JDK classes from the bootstrap classpath
    val bootstrapArtifacts = loadBootstrapArtifacts(bootClasspathToUse(log), log)

    val allArtifacts = runtimeArtifacts.map(_.artifact) ++ bootstrapArtifacts

    val projectArtifact =
      if (scanDependencies)
        classesToArtifact(runtimeArtifactsAfterExclusions.flatMap(_.classes.asScala).toMap)
      else
        toArtifact(classDirectory, log)

    if (projectArtifact.classes().isEmpty()) {
      log.info(
        "No classes found in project build directory" +
          " - did you run 'sbt compile' first?"
      )
    }

    log.debug("Checking for conflicts starting from " + projectArtifact.name().name())
    log.debug("Artifacts included in the project: ")
    for (artifact <- runtimeArtifactsAfterExclusions) {
      log.debug("    " + artifact.name().name())
    }
    log.debug("All artifacts: ")
    for (artifact <- allArtifacts) {
      log.debug("    " + artifact.name().name())
    }

    val conflictChecker = new ConflictChecker

    val conflicts =
      conflictChecker.check(
        projectArtifact,
        runtimeArtifactsAfterExclusions.asJava,
        allArtifacts.asJava
      )

    conflicts.asScala.toSeq
  }

  private def classesToArtifact(classes: Map[ClassTypeDescriptor, DeclaredClass]): Artifact = {
    new ArtifactBuilder()
      .name(new ArtifactName("project"))
      .classes(classes.asJava)
      .build()
  }

  private def loadClass(f: File): DeclaredClass = {
    val is = new FileInputStream(f)
    try com.spotify.missinglink.ClassLoader.load(is)
    finally is.close()
  }

  private def toArtifact(outputDirectory: os.Path, logger: Logger): Artifact = {
    val classes = if (os.exists(outputDirectory)) {
      logger.debug(s"Walking class directory: ${outputDirectory}")
      os
        .walk(outputDirectory)
        .filter(_.ext == "class")
        .map(path => loadClass(path.toIO))
        .map(c => c.className() -> c)
    } else {
      Seq.empty
    }

    classesToArtifact(classes.toMap)
  }

  private def bootClasspathToUse(log: Logger): String = {
    /*if (this.bootClasspath != null) {
      log.debug("using configured boot classpath: " + this.bootClasspath);
      this.bootClasspath;
    } else {*/
    val bootClasspath = System.getProperty("sun.boot.class.path")
    log.debug("derived bootclasspath: " + bootClasspath)
    bootClasspath
    /*}*/
  }

  private def constructArtifacts(cp: Seq[os.Path], log: Logger): List[ModuleArtifact] = {
    val artifactLoader = new ArtifactLoader

    def isValid(entry: File): Boolean =
      (entry.isFile && entry.getPath.endsWith(".jar")) || entry.isDirectory

    def fileToArtifact(f: os.Path): ModuleArtifact = {
      log.debug("loading artifact for path: " + f)
      ModuleArtifact(artifactLoader.load(f.toIO), Some(f))
    }

    cp.filter(c => isValid(c.toIO)).map(fileToArtifact).toList
  }

  private def loadBootstrapArtifacts(bootstrapClasspath: String, log: Logger): List[Artifact] = {
    if (bootstrapClasspath == null) {
      Java9ModuleLoader.getJava9ModuleArtifacts((s, _) => log.error(s)).asScala.toList
    } else {
      val cp = bootstrapClasspath
        .split(System.getProperty("path.separator"))
        .map(f => os.Path(f))
        .toSeq

      constructArtifacts(cp, log).map(_.artifact)
    }
  }

  private def filterConflicts[T <: PackageFilter](
    packageFilters: Seq[T],
    filterLabel: String,
    log: Logger,
    name: String,
    field: Dependency => ClassTypeDescriptor
  )(
    implicit pfs: PackageFilters[T]
  ): Seq[Conflict] => Seq[Conflict] = { input =>
    if (packageFilters.nonEmpty) {
      log.debug(s"Applying filters on $name packages: ${packageFilters.mkString(", ")}")

      def isFiltered(conflict: Conflict): Boolean = {
        val descriptor = field(conflict.dependency())
        val className = descriptor.getClassName.replace('/', '.')
        val conflictPackageName = className.substring(0, className.lastIndexOf('.'))

        pfs.apply(conflictPackageName)(packageFilters)
      }

      val filtered = input.filter(isFiltered)
      val diff = input.length - filtered.length

      if (diff != 0) {
        log.info(
          s"""
             |$diff conflicts found in ignored ${name} packages.
             |Run plugin again without the '${filterLabel} setting to see all conflicts that were found.
           """.stripMargin
        )
      }

      filtered
    } else {
      input
    }
  }

  private def outputConflicts(conflicts: Seq[Conflict], log: Logger): Unit = {
    def logLine(msg: String): Unit =
      log.error(msg)

    val descriptions = Map(
      ConflictCategory.CLASS_NOT_FOUND -> "Class being called not found",
      ConflictCategory.METHOD_SIGNATURE_NOT_FOUND -> "Method being called not found"
    )

    // group conflict by category
    val byCategory = conflicts.groupBy(_.category())

    for ((category, conflictsInCategory) <- byCategory) {
      val desc = descriptions.getOrElse(category, category.name().replace('_', ' '))
      logLine("")
      logLine("Category: " + desc)

      // next group by artifact containing the conflict
      val byArtifact = conflictsInCategory.groupBy(_.usedBy())

      for ((artifactName, conflictsInArtifact) <- byArtifact) {
        logLine("  In artifact: " + artifactName.name())

        // next group by class containing the conflict
        val byClassName = conflictsInArtifact.groupBy(_.dependency().fromClass())

        for ((classDesc, conflictsInClass) <- byClassName) {
          logLine("    In class: " + classDesc.toString())

          for (conflict <- conflictsInClass) {
            def optionalLineNumber(lineNumber: Int): String =
              if (lineNumber != 0) ":" + lineNumber else ""

            val dep = conflict.dependency()
            logLine(
              "      In method:  " +
                dep.fromMethod().prettyWithoutReturnType() +
                optionalLineNumber(dep.fromLineNumber())
            )
            logLine("      " + dep.describe())
            logLine("      Problem: " + conflict.reason())
            if (conflict.existsIn() != ConflictChecker.UNKNOWN_ARTIFACT_NAME)
              logLine("      Found in: " + conflict.existsIn().name())
            // this could be smarter about separating each blob of warnings by method, but for
            // now just output a bunch of dashes always
            logLine("      --------")
          }
        }
      }
    }
  }

  private final case class ModuleArtifact(artifact: Artifact, path: Option[os.Path] = None)

}

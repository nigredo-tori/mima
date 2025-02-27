package mimabuild

import bintray.BintrayPlugin
import com.typesafe.config.ConfigFactory
import sbt._
import sbt.Keys._
import sbt.internal.inc.ScalaInstance
import sbt.librarymanagement.{ DependencyResolution, UnresolvedWarningConfiguration, UpdateConfiguration }

object TestsPlugin extends AutoPlugin {
  object autoImport {
    // This is the key for the scala version used to compile the tests, so that we can cross test the MiMa version
    // actually being used in the sbt plugin against multiple scala compiler versions.
    // Also the base project has dependencies that don't resolve under newer versions of scala.
    val testScalaVersion = settingKey[String]("The scala version to use to compile the test classes")

    val testFunctional = taskKey[Unit]("Run the functional test")
  }
  import autoImport._

  override def extraProjects = funTestProjects ++ intTestProjects

  override def buildSettings = Seq(
    testScalaVersion := sys.props.getOrElse("mima.testScalaVersion", scalaVersion.value),
  )

  override def projectSettings = Seq(
            testFunctional := dependOnAll(funTestProjects, _ /            Test / test).value,
    IntegrationTest / test := dependOnAll(intTestProjects, _ / IntegrationTest / test).value,
  )

  private val functionalTests = LocalProject("functional-tests")

  private val V1 = config("V1").extend(Compile)
  private val V2 = config("V2").extend(Compile)

  private def testProjects(prefix: String, fileName: String, setup: Project => Project) = {
    (file("functional-tests") / "src" / prefix * dirContaining(fileName)).get().map { base =>
      Project(s"$prefix-${base.name}", base).disablePlugins(BintrayPlugin).configure(setup)
    }
  }

  private def intTestProject(p: Project) = p.settings(intTestProjectSettings)
  private def funTestProject(p: Project) = p.settings(funTestProjectSettings).configs(V1, V2)

  private lazy val funTestProjects = testProjects("test", "problems.txt", funTestProject)
  private lazy val intTestProjects = testProjects( "it" ,   "test.conf" , intTestProject)

  private def sharedTestProjectSettings = Def.settings(
    resolvers += "scala-pr-validation-snapshots" at "https://scala-ci.typesafe.com/artifactory/scala-pr-validation-snapshots/",
    scalaVersion := testScalaVersion.value,
  )

  private val runIntegrationTest = Def.task {
    val confFile = baseDirectory.value / "test.conf"
    val conf = ConfigFactory.parseFile(confFile).resolve()
    val moduleBase = conf.getString("groupId") % conf.getString("artifactId")
    val depRes = dependencyResolution.value
    val oldJar = getArtifact(depRes, moduleBase % conf.getString("v1"), streams.value.log)
    val newJar = getArtifact(depRes, moduleBase % conf.getString("v2"), streams.value.log)
    streams.value.log.info(s"Comparing $oldJar -> $newJar")
    runCollectProblemsTest(
      (functionalTests / Compile / fullClasspath).value, // the test classpath from the functionalTest project for the test
      (functionalTests / scalaInstance).value, // get a reference to the already loaded Scala classes so we get the advantage of a warm jvm
      streams.value,
      thisProjectRef.value.project,
      oldJar,
      newJar,
      baseDirectory.value,
      scalaVersion.value,
    )
  }

  private val intTestProjectSettings = Def.settings(
    sharedTestProjectSettings,
    IntegrationTest / test := runIntegrationTest.value,
  )

  // The settings for the V1 and V2 configurations (e.g. add compile and package).
  private val funTestPerConfigSettings = Def.settings(
    Defaults.configSettings, // use the normal per-configuration settings
    // but modify the source directory to be just V1/ instead of src/V1/scala/
    // scalaSource is the setting key that defines the directory for Scala sources
    // configuration gets the current configuration
    scalaSource := baseDirectory.value / configuration.value.name.toLowerCase,
  )

  private val runFunctionalTest = Def.task {
    runCollectProblemsTest(
      (functionalTests / Compile / fullClasspath).value, // the test classpath from the functionalTest project for the test
      (functionalTests / scalaInstance).value, // get a reference to the already loaded Scala classes so we get the advantage of a warm jvm
      streams.value,
      thisProjectRef.value.project,
      (V1 / packageBin).value, // package the V1 sources and get the configuration used
      (V2 / packageBin).value, // same for V2
      baseDirectory.value,
      scalaVersion.value,
    )
  }

  private val funTestProjectSettings = Def.settings(
    sharedTestProjectSettings,
    inConfig(V1)(funTestPerConfigSettings),
    inConfig(V2)(funTestPerConfigSettings),
    Test / test := runFunctionalTest.value,
  )

  private def runCollectProblemsTest(
      cp: Classpath,
      si: ScalaInstance,
      streams: TaskStreams,
      testName: String,
      oldJarOrDir: File,
      newJarOrDir: File,
      projectPath: File,
      scalaVersion: String,
  ): Unit = {
    val urls = Attributed.data(cp).map(_.toURI.toURL).toArray
    val loader = new java.net.URLClassLoader(urls, si.loader)

    val testClass = loader.loadClass("com.typesafe.tools.mima.lib.CollectProblemsTest$")
    val testRunner = testClass.getField("MODULE$").get(null).asInstanceOf[ {
      def runTest(
          testClasspath: Array[File],
          testName: String,
          oldJarOrDir: File,
          newJarOrDir: File,
          baseDir: File,
          scalaVersion: String,
      ): Unit
    }]

    // Add the scala-library to the MiMa classpath used to run this test
    val testClasspath = Attributed.data(cp).filter(_.getName.endsWith("scala-library.jar")).toArray

    try {
      import scala.language.reflectiveCalls
      testRunner.runTest(testClasspath, testName, oldJarOrDir, newJarOrDir, projectPath, scalaVersion)
      streams.log.info(s"Test '$testName' succeeded.")
    } catch {
      case e: Exception =>
        scala.Console.err.println(e.toString)
        throw new MessageOnlyException(s"'$testName' failed.")
    }
  }

  private def getArtifact(depResolver: DependencyResolution, m: ModuleID, log: Logger): File = {
    val md = depResolver.wrapDependencyInModule(m)
    val updateConf = UpdateConfiguration().withLogging(UpdateLogging.DownloadOnly)
    depResolver.update(md, updateConf, UnresolvedWarningConfiguration(), log) match {
      case Left(unresolvedWarning) =>
        import ShowLines._
        unresolvedWarning.lines.foreach(log.warn(_))
        throw unresolvedWarning.resolveException
      case Right(updateReport) =>
        val allFiles = for {
          conf <- updateReport.configurations
          module <- conf.modules
          (artifact, file) <- module.artifacts
          if artifact.name == m.name
        } yield file

        allFiles.headOption getOrElse sys.error(s"Could not resolve artifact: $m")
    }
  }

  private def dependOnAll(projects: Seq[Project], f: Project => TaskKey[Unit]): Def.Initialize[Task[Unit]] =
    Def.taskDyn {
      val structure = Project.structure(state.value)
      val allTasks = projects.flatMap(p => f(p).get(structure.data))
      Def.task(allTasks.join.map(_ => ()).value)
    }

  private def dirContaining(oracleFilename: String): FileFilter = {
    DirectoryFilter && new SimpleFileFilter(_.list.contains(oracleFilename))
  }
}

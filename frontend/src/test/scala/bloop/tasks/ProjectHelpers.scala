package bloop.tasks

import java.io.IOException
import java.nio.charset.Charset
import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes

import bloop.{Project, ScalaInstance}
import bloop.io.AbsolutePath
import bloop.logging.Logger

object ProjectHelpers {

  def projectDir(base: Path, name: String) = base.resolve(name)
  def sourcesDir(base: Path, name: String) = projectDir(base, name).resolve("src")
  def classesDir(base: Path, name: String) = projectDir(base, name).resolve("classes")

  def rebase(from: Path, to: Path, project: Project): Project = {
    def work(path: AbsolutePath): AbsolutePath = {
      val newPath = Paths.get(path.toString.replaceFirst(from.toString, to.toString))
      AbsolutePath(newPath)
    }

    project.copy(
      classpath = project.classpath.map(work),
      classesDir = work(project.classesDir),
      sourceDirectories = project.sourceDirectories.map(work),
      tmp = work(project.tmp),
      origin = project.origin.map(work)
    )
  }

  def loadTestProject(name: String, logger: Logger): Map[String, Project] = {
    val base = getClass.getClassLoader.getResources(s"projects/$name") match {
      case res if res.hasMoreElements => Paths.get(res.nextElement.getFile)
      case _ => throw new Exception("No projects to test?")
    }

    val configDir = base.resolve("bloop-config")
    val baseDirectoryFile = configDir.resolve("base-directory")
    assert(Files.exists(configDir) && Files.exists(baseDirectoryFile))
    val testBaseDirectory = {
      val contents = Files.readAllLines(baseDirectoryFile)
      assert(!contents.isEmpty)
      contents.get(0)
    }
    def rebase(baseDirectory: String, proj: Project) = {
      // We need to remove the `/private` prefix that's SOMETIMES present in OSX (!??!)
      val testBaseDirectory = Paths.get(baseDirectory.stripPrefix("/private"))

      val proj0 = ProjectHelpers.rebase(Paths.get("/private"), Paths.get(""), proj)

      // Rebase the scala instance if it comes from sbt's boot directory
      val proj1 = ProjectHelpers.rebase(
        testBaseDirectory.resolve("global").resolve("boot"),
        Paths.get(sys.props("user.home")).resolve(".sbt").resolve("boot"),
        proj0)

      // Rebase the rest of the paths
      val proj2 = ProjectHelpers.rebase(testBaseDirectory, base, proj1)
      proj2
    }

    val loadedProjects = Project.fromDir(AbsolutePath(configDir), logger)
    loadedProjects.mapValues(rebase(testBaseDirectory, _))
  }

  def withProjects[T](projectStructures: Map[String, Map[String, String]],
                      dependencies: Map[String, Set[String]],
                      scalaInstance: ScalaInstance = CompilationHelpers.scalaInstance)(
      op: Map[String, Project] => T): T = {
    withTemporaryDirectory { temp =>
      val projects = projectStructures.map {
        case (name, sources) =>
          val deps = dependencies.getOrElse(name, Set.empty)
          name -> makeProject(temp, name, sources, deps, scalaInstance)
      }
      op(projects)
    }
  }

  def noPreviousResult(project: Project): Boolean = !hasPreviousResult(project)
  def hasPreviousResult(project: Project): Boolean = {
    project.previousResult.analysis.isPresent &&
    project.previousResult.setup.isPresent
  }

  def makeProject(baseDir: Path,
                  name: String,
                  sources: Map[String, String],
                  dependencies: Set[String],
                  scalaInstance: ScalaInstance): Project = {
    val baseDirectory = projectDir(baseDir, name)
    val (srcs, classes) = makeProjectStructure(baseDir, name)
    val tempDir = baseDirectory.resolve("tmp")
    Files.createDirectories(tempDir)

    val target = classesDir(baseDir, name)
    val depsTargets = (dependencies.map(classesDir(baseDir, _))).toArray.map(AbsolutePath.apply)
    val classpath = depsTargets ++ scalaInstance.allJars.map(AbsolutePath.apply)
    val sourceDirectories = Array(AbsolutePath(srcs))
    writeSources(srcs, sources)
    Project(
      name = name,
      baseDirectory = AbsolutePath(baseDirectory),
      dependencies = dependencies.toArray,
      scalaInstance = scalaInstance,
      classpath = classpath,
      classesDir = AbsolutePath(target),
      scalacOptions = Array.empty,
      javacOptions = Array.empty,
      sourceDirectories = sourceDirectories,
      previousResult = CompilationHelpers.emptyPreviousResult,
      testFrameworks = Array.empty,
      tmp = AbsolutePath(tempDir),
      origin = None
    )
  }

  def makeProjectStructure[T](base: Path, name: String): (Path, Path) = {
    val srcs = sourcesDir(base, name)
    val classes = classesDir(base, name)
    Files.createDirectories(srcs)
    Files.createDirectories(classes)
    (srcs, classes)
  }

  def writeSources[T](srcDir: Path, sources: Map[String, String]): Unit = {
    sources.foreach {
      case (name, contents) =>
        val writer = Files.newBufferedWriter(srcDir.resolve(name), Charset.forName("UTF-8"))
        try writer.write(contents)
        finally writer.close()
    }
  }

  def withTemporaryDirectory[T](op: Path => T): T = {
    val temp = Files.createTempDirectory("tmp-test")
    try op(temp)
    finally delete(temp)
  }

  def delete(path: Path): Unit = {
    Files.walkFileTree(
      path,
      new SimpleFileVisitor[Path] {
        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
          Files.delete(file)
          FileVisitResult.CONTINUE
        }

        override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
          Files.delete(dir)
          FileVisitResult.CONTINUE
        }
      }
    )
    ()
  }
}

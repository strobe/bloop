package bloop.cli

import java.nio.file.Path

sealed trait Command

object Commands {
  case class Compile(
      baseDir: Path,
      project: String,
      batch: Boolean = false,
      parallel: Boolean = true
  ) extends Command

  case class Clean(
      baseDir: Path,
      projects: List[String]
  ) extends Command
}
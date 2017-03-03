package org.jetbrains.plugins.scala.project

import com.intellij.util.net.HttpConfigurable
import org.jetbrains.plugins.scala.project.Platform.{Dotty, Scala}

import scala.io.Source
import scala.util.Try
import scala.util.matching.Regex

/**
 * @author Pavel Fatin
 */
object Versions  {
  val DefaultScalaVersion: String = Entity.Scala.defaultVersion

  val DefaultDottyVersion: String = Entity.Dotty.defaultVersion

  val DefaultSbtVersion: String = Entity.Sbt.defaultVersion

  def loadScalaVersions(platform: Platform): Array[String] = platform match {
    case Scala => loadVersionsOf(Entity.Scala)
    case Dotty => loadVersionsOf(Entity.Dotty)
  }

  def loadSbtVersions: Array[String] = loadVersionsOf(Entity.Sbt)

  private def loadVersionsOf(entity: Entity): Array[String] = {
    loadVersionsFrom(entity.url, {
      case entity.pattern(number) => number
    })
      .getOrElse(entity.hardcodedVersions)
      .map(Version(_))
      .filter(_ >= entity.minVersion)
      .sortWith(_ >= _)
      .map(_.number)
      .toArray
  }

  private def loadVersionsFrom(url: String, filter: PartialFunction[String, String]): Try[Seq[String]] = {
    loadLinesFrom(url).map { lines => lines.collect(filter) }
  }

  private def loadLinesFrom(url: String): Try[Seq[String]] = {
    Try(HttpConfigurable.getInstance().openHttpConnection(url)).map { connection =>
      try {
        Source.fromInputStream(connection.getInputStream).getLines().toVector
      } finally {
        connection.disconnect()
      }
    }
  }

  private case class Entity(url: String, pattern: Regex, minVersion: Version, hardcodedVersions: Seq[String]) {
    def defaultVersion: String = hardcodedVersions.last
  }

  private object Entity {
    val Scala = Entity("http://repo1.maven.org/maven2/org/scala-lang/scala-compiler/",
      ".+>(\\d+\\.\\d+\\.\\d+)/<.*".r,
      Version("2.8.0"),
      Seq("2.8.2", "2.9.3", "2.10.6", "2.11.8", "2.12.1"))

    val Sbt = Entity("https://dl.bintray.com/typesafe/ivy-releases/org.scala-sbt/sbt-launch/",
      ".+>(\\d+\\.\\d+\\.\\d+)/<.*".r,
      Version("0.12.0"),
      Seq("0.12.4", "0.13.13"))

    val Dotty = Entity(s"http://repo1.maven.org/maven2/ch/epfl/lamp/dotty_2.11/",
      """.+>(\d+.\d+.+)/<.*""".r,
      Version("0.1-SNAPSHOT"),
      Seq("0.1.1-SNAPSHOT"))
  }
}
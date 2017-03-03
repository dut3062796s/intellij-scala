package org.jetbrains.plugins.scala
package project
package template

import java.io.File
import java.util
import java.util.Collections
import javax.swing.JComponent

import com.intellij.openapi.roots.libraries.NewLibraryConfiguration
import com.intellij.openapi.roots.ui.configuration.libraries.CustomLibraryDescription
import com.intellij.openapi.roots.ui.configuration.projectRoot.LibrariesContainer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VfsUtilCore.virtualToIoFile
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.plugins.scala.project.template.Artifact._

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
  * @author Pavel Fatin
  */
object ScalaLibraryDescription extends CustomLibraryDescription {
  def getSuitableLibraryKinds: util.Set[ScalaLibraryKind.type] = Collections.singleton(ScalaLibraryKind)

  def createNewLibrary(parentComponent: JComponent, contextDirectory: VirtualFile): NewLibraryConfiguration = {
    implicit val ordering = implicitly[Ordering[Version]].reverse

    def sdks = localSkdsIn(virtualToIoFile(contextDirectory)).map(SdkChoice(_, "Project")) ++
      systemSdks.sortBy(_.version).map(SdkChoice(_, "System")) ++
      ivySdks.sortBy(_.version).map(SdkChoice(_, "Ivy")) ++
      mavenSdks.sortBy(_.version).map(SdkChoice(_, "Maven"))

    val dialog = new SdkSelectionDialog(parentComponent, () => sdks.sortBy(_.sdk.platform).asJava)

    Option(dialog.open()).map(_.createNewLibraryConfiguration()).orNull
  }

  override def getDefaultLevel = LibrariesContainer.LibraryLevel.GLOBAL

  private def localSkdsIn(directory: File): Seq[ScalaSdkDescriptor] =
    Seq(directory / "lib").flatMap(sdkIn)

  def systemSdks: Seq[ScalaSdkDescriptor] =
    systemScalaRoots.flatMap(path => sdkIn(new File(path)).toSeq)

  private def systemScalaRoots: Seq[String] = {
    val fromApps = systemAppRoots.filter(exists).flatMap(findScalaDirectoriesIn)

    val fromHome = env("SCALA_HOME")

    val fromCommandPath = env("PATH").flatMap(findScalaInCommandPath)

    (fromApps ++ fromHome ++ fromCommandPath).distinct.filter(exists)
  }

  private def systemAppRoots: Seq[String] = if (SystemInfo.isWindows) {
    env("ProgramFiles").toSeq ++ env("ProgramFiles(x86)").toSeq
  } else if (SystemInfo.isMac) {
    Seq("/opt/")
  } else if (SystemInfo.isLinux) {
    Seq("/usr/share/java/")
  } else {
    Seq.empty
  }

  private def env(name: String): Option[String] = Option(System.getenv(name))

  private def exists(path: String): Boolean = new File(path).exists

  private def findScalaDirectoriesIn(directory: String): Seq[String] = {
    val subdirectories = new File(directory).listFiles.toSeq
    subdirectories.filter(_.getName.toLowerCase.startsWith("scala")).map(_.getPath)
  }

  private def findScalaInCommandPath(path: String): Option[String] =
    path.split(File.pathSeparator)
      .find(_.toLowerCase.contains("scala"))
      .map(_.replaceFirst("""[/\\]?bin[/\\]?$""", ""))

  private def sdkIn(root: File): Option[ScalaSdkDescriptor] = {
    val components = Component.discoverIn(root.allFiles, Artifact.ScalaArtifacts)

    ScalaSdkDescriptor.from(components).right.toOption
  }

  def mavenSdks: Seq[ScalaSdkDescriptor] = {
    val root = new File(System.getProperty("user.home")) / ".m2"

    val scalaFiles = (root / "repository" / "org" / "scala-lang").allFiles

    scalaSdksIn(scalaFiles)
  }

  def ivySdks: Seq[ScalaSdkDescriptor] = {
    val root = new File(System.getProperty("user.home")) / ".ivy2"

    val scalaFiles = (root / "cache" / "org.scala-lang").allFiles

    val dottyFiles = scalaFiles ++
      (root / "cache" / "me.d-d" / "scala-compiler").allFiles ++
      (root / "cache" / "ch.epfl.lamp").allFiles ++
      (root / "cache" / "jline").allFiles

    scalaSdksIn(scalaFiles) ++ dottySdksIn(dottyFiles)
  }

  private def scalaSdksIn(files: Seq[File]): Seq[ScalaSdkDescriptor] = {
    val components = Component.discoverIn(files, Artifact.ScalaArtifacts)

    components.groupBy(_.version).mapValues(ScalaSdkDescriptor.from).toSeq.collect {
      case (Some(_), Right(sdk)) => sdk
    }
  }

  private def dottySdksIn(files: Seq[File]): Seq[ScalaSdkDescriptor] = {
    val components = Component.discoverIn(files, Artifact.DottyArtifacts)

    val patchedCompilers = components.filter {
      case Component(ScalaCompiler, Kind.Binaries, _, file) if file.getAbsolutePath.contains("me.d-d") => true
      case _ => false
    }

    if (patchedCompilers.isEmpty) Seq.empty
    else {
      val compilerComponent = patchedCompilers.maxBy(_.version)

      val dottyComponents = components.filter {
        case Component(DottyInterfaces | DottyCompiler | DottyLibrary, _, Some(_), _) => true
        case _ => false
      }

      val otherComponents = components.filter {
        case Component(ScalaLibrary | ScalaReflect, _, Some(Version("2.11.5")), _) => true
        case Component(JLine, _, Some(Version("2.12")), _) => true
        case _ => false
      }

      dottyComponents.groupBy(_.version).values
        .map(components => ScalaSdkDescriptor.from(components ++ otherComponents :+ compilerComponent))
        .flatMap(_.right.toSeq)
        .toSeq
    }
  }

}

case class SdkChoice(sdk: ScalaSdkDescriptor, source: String)
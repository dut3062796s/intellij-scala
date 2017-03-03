package org.jetbrains.plugins.scala
package lang
package refactoring
package util

import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi._
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.formatting.settings.ScalaCodeStyleSettings
import org.jetbrains.plugins.scala.lang.formatting.settings.ScalaCodeStyleSettings.EXCLUDE_PREFIX
import org.jetbrains.plugins.scala.lang.lexer.{ScalaLexer, ScalaTokenTypes}
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScNamedElement

import scala.reflect.NameTransformer

/**
 * User: Alexander Podkhalyuzin
 * Date: 24.06.2008
 */
object ScalaNamesUtil {
  val keywordNames = ScalaTokenTypes.KEYWORDS.getTypes.map(_.toString).toSet

  private val lexerCache = new ThreadLocal[ScalaLexer] {
    override def initialValue(): ScalaLexer = new ScalaLexer()
  }

  private def checkGeneric(text: String, predicate: ScalaLexer => Boolean): Boolean = {
//    ApplicationManager.getApplication.assertReadAccessAllowed() - looks like we don't need it
    if (text == null || text == "") return false

    val lexer = lexerCache.get()
    lexer.start(text, 0, text.length(), 0)
    if (!predicate(lexer)) return false
    lexer.advance()
    lexer.getTokenType == null
  }

  def isOpCharacter(c : Char) : Boolean = {
    c match {
      case '~' | '!' | '@' | '#' | '%' | '^' | '*' | '+' | '-' | '<' | '>' | '?' | ':' | '=' | '&' | '|' | '/' | '\\' =>
        true
      case ch =>
        Character.getType(ch) == Character.MATH_SYMBOL.toInt || Character.getType(ch) == Character.OTHER_SYMBOL.toInt
    }
  }

  def isIdentifier(text: String): Boolean = {
    checkGeneric(text, lexer => lexer.getTokenType == ScalaTokenTypes.tIDENTIFIER)
  }

  def isQualifiedName(text: String): Boolean = {
    if (StringUtil.isEmpty(text)) return false
    text.split('.').forall(isIdentifier)
  }

  def isKeyword(text: String): Boolean = keywordNames.contains(text)

  def isOperatorName(text: String): Boolean = isIdentifier(text) && isOpCharacter(text(0))

  def scalaName(element: PsiElement): String = element match {
    case scNamed: ScNamedElement => scNamed.name
    case psiNamed: PsiNamedElement => psiNamed.getName
  }

  def qualifiedName(named: PsiNamedElement): Option[String] = {
    ScalaPsiUtil.nameContext(named) match {
      case pack: PsiPackage => Some(pack.getQualifiedName)
      case clazz: PsiClass => Some(clazz.qualifiedName)
      case memb: PsiMember =>
        val containingClass = memb.containingClass
        if (containingClass != null && containingClass.qualifiedName != null && memb.hasModifierProperty(PsiModifier.STATIC)) {
          Some(Seq(containingClass.qualifiedName, named.name).filter(_ != "").mkString("."))
        } else None
      case _ => None
    }
  }

  object isBackticked {
    def unapply(named: ScNamedElement): Option[String] = {
      val name = named.name
      isBacktickedName.unapply(name)
    }
  }

  object isBacktickedName {
    def unapply(name: String): Option[String] = {
      if (name == null || name.isEmpty) None
      else if (name != "`" && name.startsWith("`") && name.endsWith("`")) Some(name.substring(1, name.length - 1))
      else None
    }
  }

  def splitName(name: String): Seq[String] = {
    if (name == null || name.isEmpty) Seq.empty
    else if (name.contains(".")) name.split("\\.")
    else Seq(name)
  }

  def toJavaName(name: String): String = {
    val toEncode = name match {
      case ScalaNamesUtil.isBacktickedName(s) => s
      case _ => name
    }
    Option(toEncode).map(NameTransformer.encode).orNull
  }

  def clean(name: String): String = {
    val toDecode = name match {
      case ScalaNamesUtil.isBacktickedName(s) => s
      case _ => name
    }
    Option(toDecode).map(NameTransformer.decode).orNull
  }

  def cleanFqn(fqn: String): String =
    splitName(fqn).map(clean).mkString(".")

  def equivalentFqn(l: String, r: String): Boolean =
    l == r || cleanFqn(l) == cleanFqn(r)

  def equivalent(l: String, r: String): Boolean =
    l == r || clean(l) == clean(r)

  def escapeKeywordsFqn(fqn: String): String =
    splitName(fqn).map(escapeKeyword).mkString(".")

  def escapeKeyword(s: String): String =
    if (ScalaNamesUtil.isKeyword(s)) s"`$s`" else s

  def nameFitToPatterns(qualName: String, patterns: Array[String], strict: Boolean): Boolean = {
    val (exclude, include) = patterns.toSeq.partition(_.startsWith(EXCLUDE_PREFIX))

    !exclude.exists(excl => fitToPattern(excl.stripPrefix(EXCLUDE_PREFIX), qualName, strict)) &&
      include.exists(fitToPattern(_, qualName, strict))
  }

  private def fitToPattern(pattern: String, qualName: String, strict: Boolean): Boolean = {

    @scala.annotation.tailrec
    def fitToUnderscorePattern(pattern: String, qualName: String, strict: Boolean): Boolean = {
      val subPattern = pattern.stripSuffix("._")
      val dotIdx = qualName.lastIndexOf('.')

      if (dotIdx <= 0) false
      else {
        val subName = qualName.substring(0, dotIdx)

        if (subPattern.endsWith("._"))
          fitToUnderscorePattern(subPattern, subName, strict = true)
        else if (strict)
          subName == subPattern
        else
          subName.startsWith(subPattern)
      }
    }

    if (pattern.endsWith("._"))
      fitToUnderscorePattern(pattern, qualName, strict)
    else qualName == pattern
  }
}

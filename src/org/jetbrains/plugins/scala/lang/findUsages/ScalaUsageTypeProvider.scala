package org.jetbrains.plugins.scala
package lang
package findUsages

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil.isAncestor
import com.intellij.usages.impl.rules.UsageType._
import com.intellij.usages.impl.rules.{UsageType, UsageTypeProviderEx}
import com.intellij.usages.{PsiElementUsageTarget, UsageTarget}
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil.{MethodValue, getParentOfType}
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns._
import org.jetbrains.plugins.scala.lang.psi.api.base.types.{ScSelfTypeElement, ScTypeArgs, ScTypeElement}
import org.jetbrains.plugins.scala.lang.psi.api.base.{ScAccessModifier, ScReferenceElement}
import org.jetbrains.plugins.scala.lang.psi.api.expr._
import org.jetbrains.plugins.scala.lang.psi.api.statements._
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.{ScClassParameter, ScParameter, ScTypeParam}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.ScImportExpr
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.templates.{ScTemplateBody, ScTemplateParents}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTemplateDefinition
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.{ScEarlyDefinitions, ScPackaging}
import org.jetbrains.plugins.scala.lang.psi.impl.expr.ScInterpolatedStringPartReference
import org.jetbrains.plugins.scala.lang.resolve.ScalaResolveResult

import scala.language.implicitConversions

final class ScalaUsageTypeProvider extends UsageTypeProviderEx {

  import ScalaUsageTypeProvider._

  def getUsageType(element: PsiElement): UsageType =
    getUsageType(element, UsageTarget.EMPTY_ARRAY)

  // TODO more of these, including Scala specific: case class/object, pattern match, type ascription, ...
  def getUsageType(element: PsiElement, targets: Array[UsageTarget]): UsageType =
    element.containingScalaFile.flatMap { _ =>
      (element, targets) match {
        case (referenceElement: ScReferenceElement, Array(only: PsiElementUsageTarget))
          if isConstructorPatternReference(referenceElement) && !referenceElement.isReferenceTo(only.getElement) =>
          Some(parameterInPattern)
        case _ =>
          element.withParentsInFile
            .flatMap(usageType)
            .headOption
      }
    }.orNull
}

object ScalaUsageTypeProvider {

  private def usageType(element: PsiElement): Option[UsageType] =
    Option(nullableUsageType(element))

  private def isConstructorPatternReference(element: ScReferenceElement): Boolean = element.resolve() match {
    case pattern: ScBindingPattern => getParentOfType(pattern, classOf[ScConstructorPattern], classOf[ScInfixPattern]) != null
    case _ => false
  }

  implicit def stringToUsageType(name: String): UsageType = new UsageType(name)

  private[this] val extractor: UsageType = "Extractor"
  private[this] val classTypedPattern: UsageType = "Typed Pattern"
  private[this] val typedStatement: UsageType = "Typed Statement"
  private[this] val methodApply: UsageType = "Method `apply`"
  private[this] val thisReference: UsageType = "This Reference"
  private[this] val accessModifier: UsageType = "Access Modifier"
  private[this] val packageClause: UsageType = "Package Clause"
  private[this] val functionExpression: UsageType = "Function expression"
  private[this] val namedParameter: UsageType = "Named parameter"
  private[this] val prefixInterpolatedString: UsageType = "Interpolated string prefix"
  private val parameterInPattern: UsageType = "Parameter in pattern"
  private[this] val selfType: UsageType = "Self type"
  private[this] val typeBound: UsageType = "Type bound"
  private[this] val typeAlias: UsageType = "Type alias"
  private[this] val secondaryConstructor: UsageType = "Secondary constructor"

  private[this] def nullableUsageType(element: PsiElement): UsageType = {
    def isAppropriate(parent: PsiElement): Boolean = isAncestor(parent, element, false)

    def existsAppropriate(maybeParent: Option[PsiElement]): Boolean = maybeParent.exists(isAppropriate)

    element match {
      case _: ScImportExpr => CLASS_IMPORT
      case typeArgs: ScTypeArgs => typeArgsUsageType(typeArgs)
      case templateParents: ScTemplateParents => templateParentsUsageType(templateParents)
      case parameter: ScParameter if isAppropriate(parameter) => CLASS_METHOD_PARAMETER_DECLARATION
      case pattern: ScPattern => forPattern(pattern)
      case typeElement: ScTypeElement => typeUsageType(typeElement)
      case _: ScInterpolatedStringPartReference => prefixInterpolatedString
      case expression: ScReferenceExpression => referenceExpressionUsageType(expression)
      case expression: ScAnnotationExpr if existsAppropriate(expression.constr.reference) => ANNOTATION
      case reference: ScThisReference if existsAppropriate(reference.reference) => thisReference
      case reference: ScSuperReference if existsAppropriate(reference.reference) => DELEGATE_TO_SUPER
      case _: ScAccessModifier => accessModifier
      case packaging: ScPackaging if existsAppropriate(packaging.reference) => packageClause
      case assignment: ScAssignStmt if isAppropriate(assignment.getLExpression) =>
        if (assignment.isNamedParameter) namedParameter else WRITE
      case MethodValue(_) => functionExpression
      case _: ScBlock | _: ScTemplateBody | _: ScEarlyDefinitions => READ
      case invocation: ScSelfInvocation if !isAppropriate(invocation.args.orNull) => secondaryConstructor
      case _ => null
    }
  }

  private[this] def typeArgsUsageType(typeArguments: ScTypeArgs): UsageType =
    Option(typeArguments.getParent).collect {
      case ScGenericCall(reference: ScReferenceExpression, Seq(_)) => reference.refName
    }.collect {
      case "isInstanceOf" => CLASS_INSTANCE_OF
      case "asInstanceOf" => CLASS_CAST_TO
      case "classOf" => CLASS_CLASS_OBJECT_ACCESS
    }.getOrElse(TYPE_PARAMETER)

  private[this] def templateParentsUsageType(tp: ScTemplateParents): UsageType =
    getParentOfType(tp, classOf[ScTemplateDefinition], classOf[ScAnnotation]) match {
      case templateDefinition: ScNewTemplateDefinition =>
        if (templateDefinition.extendsBlock.isAnonymousClass) CLASS_ANONYMOUS_NEW_OPERATOR else CLASS_NEW_OPERATOR
      case _: ScTemplateDefinition => CLASS_EXTENDS_IMPLEMENTS_LIST
      case _ => null
    }

  private[this] def forPattern(pattern: ScPattern): UsageType =
    Option(getParentOfType(pattern, classOf[ScCatchBlock]))
      .collect {
        case ScCatchBlock(clauses) => clauses
      }
      .filter(_.caseClauses.flatMap(_.pattern).exists(isAncestor(_, pattern, false)))
      .map(_ => CLASS_CATCH_CLAUSE_PARAMETER_DECLARATION)
      .getOrElse {
        pattern match {
          case ScTypedPattern(typeElement) if isAncestor(typeElement, pattern, false) => classTypedPattern
          case _: ScConstructorPattern | _: ScInfixPattern => extractor
          case _ => null
        }
      }

  private[this] def typeUsageType(typeElement: ScTypeElement): UsageType = {
    def isAppropriate(maybeTypeElement: Option[ScTypeElement]) = maybeTypeElement.contains(typeElement)

    typeElement.getParent match {
      case function: ScFunction if isAppropriate(function.returnTypeElement) =>
        CLASS_METHOD_RETURN_TYPE
      case valueOrVariable: ScValueOrVariable if isAppropriate(valueOrVariable.typeElement) =>
        if (valueOrVariable.isLocal) CLASS_LOCAL_VAR_DECLARATION else CLASS_FIELD_DECLARATION
      case classParameter: ScClassParameter if isAppropriate(classParameter.typeElement) && classParameter.isEffectiveVal =>
        CLASS_FIELD_DECLARATION
      case typedStmt: ScTypedStmt if isAppropriate(typedStmt.typeElement) =>
        typedStatement
      case _: ScSelfTypeElement => selfType
      case _: ScTypeAliasDeclaration | _: ScTypeParam => typeBound
      case _: ScTypeAliasDefinition => typeAlias
      case _ => null
    }
  }

  private[this] def referenceExpressionUsageType(expression: ScReferenceExpression): UsageType = {
    def resolvedElement(result: ScalaResolveResult) =
      result.innerResolveResult
        .getOrElse(result).element

    import ScFunction.Name.Apply
    expression.bind()
      .map(resolvedElement)
      .collect {
        case function: ScFunction if function.name == Apply && expression.refName != Apply => methodApply
        case definition: ScFunctionDefinition if isAncestor(definition, expression, false) => RECURSION
      }.orNull
  }
}
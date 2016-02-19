package org.jetbrains.plugins.scala.conversion.ast

/**
 * Created by Kate Ustyuzhanina
 * on 10/27/15
 */
case class Variable(modifiers: IntermediateNode, name: IntermediateNode, ftype: IntermediateNode,
                    isVar: Boolean, initalaizer: Option[IntermediateNode]) extends IntermediateNode with TypedElement{
    override def getType: IntermediateNode = ftype.asInstanceOf[TypedElement].getType
}
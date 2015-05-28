/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.psi.psiUtil

import com.intellij.extapi.psi.StubBasedPsiElementBase
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.search.PsiSearchScopeUtil
import com.intellij.psi.search.SearchScope
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.JetNodeTypes
import org.jetbrains.kotlin.diagnostics.DiagnosticUtils
import org.jetbrains.kotlin.lexer.JetTokens
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.stubs.KotlinClassOrObjectStub
import org.jetbrains.kotlin.resolve.calls.CallTransformer.CallForImplicitInvoke
import org.jetbrains.kotlin.types.expressions.OperatorConventions
import org.jetbrains.kotlin.utils.addToStdlib.lastIsInstanceOrNull
import java.util.ArrayList
import java.util.Collections
import kotlin.test.assertTrue

public fun JetCallElement.getCallNameExpression(): JetSimpleNameExpression? {
    val calleeExpression = getCalleeExpression() ?: return null

    return when (calleeExpression) {
        is JetSimpleNameExpression -> calleeExpression
        is JetConstructorCalleeExpression -> calleeExpression.getConstructorReferenceExpression()
        else -> null
    }
}

public fun JetClassOrObject.effectiveDeclarations(): List<JetDeclaration> =
        when(this) {
            is JetClass ->
                getDeclarations() + getPrimaryConstructorParameters().filter { p -> p.hasValOrVarNode() }
            else ->
                getDeclarations()
        }

public fun JetClass.isAbstract(): Boolean = isInterface() || hasModifier(JetTokens.ABSTRACT_KEYWORD)

public fun JetElement.blockExpressionsOrSingle(): Sequence<JetElement> =
        if (this is JetBlockExpression) getStatements().asSequence() else sequenceOf(this)

public fun JetExpression.lastBlockStatementOrThis(): JetExpression
        = (this as? JetBlockExpression)?.getStatements()?.lastIsInstanceOrNull<JetExpression>() ?: this

/**
 * Returns the list of unqualified names that are indexed as the superclass names of this class. For the names that might be imported
 * via an aliased import, includes both the original and the aliased name (reference resolution during inheritor search will sort this out).
 *
 * @return the list of possible superclass names
 */
public fun StubBasedPsiElementBase<out KotlinClassOrObjectStub<out JetClassOrObject>>.getSuperNames(): List<String> {
    fun addSuperName(result: MutableList<String>, referencedName: String): Unit {
        result.add(referencedName)

        val file = getContainingFile()
        if (file is JetFile) {
            val directive = file.findImportByAlias(referencedName)
            if (directive != null) {
                var reference = directive.getImportedReference()
                while (reference is JetDotQualifiedExpression) {
                    reference = reference.getSelectorExpression()
                }
                if (reference is JetSimpleNameExpression) {
                    result.add(reference.getReferencedName())
                }
            }
        }
    }

    assertTrue(this is JetClassOrObject)

    val stub = getStub()
    if (stub != null) {
        return stub.getSuperNames()
    }

    val specifiers = (this as JetClassOrObject).getDelegationSpecifiers()
    if (specifiers.isEmpty()) return Collections.emptyList<String>()

    val result = ArrayList<String>()
    for (specifier in specifiers) {
        val superType = specifier.getTypeAsUserType()
        if (superType != null) {
            val referencedName = superType.getReferencedName()
            if (referencedName != null) {
                addSuperName(result, referencedName)
            }
        }
    }

    return result
}

public fun JetClass.isInheritable(): Boolean {
    return isInterface() || hasModifier(JetTokens.OPEN_KEYWORD) || hasModifier(JetTokens.ABSTRACT_KEYWORD)
}

public fun JetDeclaration.isOverridable(): Boolean {
    val parent = getParent()
    if (!(parent is JetClassBody || parent is JetParameterList)) return false

    val klass = parent.getParent()
    if (!(klass is JetClass && klass.isInheritable())) return false

    if (hasModifier(JetTokens.FINAL_KEYWORD) || hasModifier(JetTokens.PRIVATE_KEYWORD)) return false

    return klass.isInterface() ||
        hasModifier(JetTokens.ABSTRACT_KEYWORD) || hasModifier(JetTokens.OPEN_KEYWORD) || hasModifier(JetTokens.OVERRIDE_KEYWORD)
}

public fun JetDeclaration.isExtensionDeclaration(): Boolean {
    val callable: JetCallableDeclaration? = when (this) {
        is JetNamedFunction, is JetProperty -> this as JetCallableDeclaration
        is JetPropertyAccessor -> getNonStrictParentOfType<JetProperty>()
        else -> null
    }

    return callable?.getReceiverTypeReference() != null
}

public fun JetClassOrObject.isObjectLiteral(): Boolean = this is JetObjectDeclaration && isObjectLiteral()

public fun PsiElement.parameterIndex(): Int {
    val parent = getParent()
    return when {
        this is JetParameter && parent is JetParameterList -> parent.getParameters().indexOf(this)
        this is PsiParameter && parent is PsiParameterList -> parent.getParameterIndex(this)
        else -> -1
    }
}

/**
 * Returns enclosing qualifying element for given [[JetSimpleNameExpression]]
 * ([[JetQualifiedExpression]] or [[JetUserType]] or original expression)
 */
public fun JetSimpleNameExpression.getQualifiedElement(): JetElement {
    val baseExpression: JetElement = (getParent() as? JetCallExpression) ?: this
    val parent = baseExpression.getParent()
    return when (parent) {
        is JetQualifiedExpression -> if (parent.getSelectorExpression().isAncestor(baseExpression)) parent else baseExpression
        is JetUserType -> if (parent.getReferenceExpression().isAncestor(baseExpression)) parent else baseExpression
        else -> baseExpression
    }
}

public fun JetSimpleNameExpression.getTopmostParentQualifiedExpressionForSelector(): JetQualifiedExpression? {
    return sequence<JetExpression>(this) {
        val parentQualified = it.getParent() as? JetQualifiedExpression
        if (parentQualified?.getSelectorExpression() == it) parentQualified else null
    }.last() as? JetQualifiedExpression
}

/**
 * Returns rightmost selector of the qualified element (null if there is no such selector)
 */
public fun JetElement.getQualifiedElementSelector(): JetElement? {
    return when (this) {
        is JetSimpleNameExpression -> this
        is JetCallExpression -> getCalleeExpression()
        is JetQualifiedExpression -> {
            val selector = getSelectorExpression()
            if (selector is JetCallExpression) selector.getCalleeExpression() else selector
        }
        is JetUserType -> getReferenceExpression()
        else -> null
    }
}

public fun PsiDirectory.getPackage(): PsiPackage? = JavaDirectoryService.getInstance()!!.getPackage(this)

public fun JetModifierListOwner.isPrivate(): Boolean = hasModifier(JetTokens.PRIVATE_KEYWORD)

public fun JetSimpleNameExpression.getReceiverExpression(): JetExpression? {
    val parent = getParent()
    when {
        parent is JetQualifiedExpression && !isImportDirectiveExpression() -> {
            val receiverExpression = parent.getReceiverExpression()
            // Name expression can't be receiver for itself
            if (receiverExpression != this) {
                return receiverExpression
            }
        }
        parent is JetCallExpression -> {
            //This is in case `a().b()`
            val callExpression = parent
            val grandParent = callExpression.getParent()
            if (grandParent is JetQualifiedExpression) {
                val parentsReceiver = grandParent.getReceiverExpression()
                if (parentsReceiver != callExpression) {
                    return parentsReceiver
                }
            }
        }
        parent is JetBinaryExpression && parent.getOperationReference() == this -> {
            return if (parent.getOperationToken() in OperatorConventions.IN_OPERATIONS) parent.getRight() else parent.getLeft()
        }
        parent is JetUnaryExpression && parent.getOperationReference() == this -> {
            return parent.getBaseExpression()!!
        }
        parent is JetUserType -> {
            val qualifier = parent.getQualifier()
            if (qualifier != null) {
                return qualifier.getReferenceExpression()!!
            }
        }
    }
    return null
}

public fun JetSimpleNameExpression.isImportDirectiveExpression(): Boolean {
    val parent = getParent()
    if (parent == null) {
        return false
    }
    else {
        return parent is JetImportDirective || parent.getParent() is JetImportDirective
    }
}

public fun JetElement.getTextWithLocation(): String = "'${this.getText()}' at ${DiagnosticUtils.atLocation(this)}"

public fun JetExpression.isFunctionLiteralOutsideParentheses(): Boolean {
    val parent = getParent()
    return when (parent) {
        is JetFunctionLiteralArgument -> true
        is JetLabeledExpression -> parent.isFunctionLiteralOutsideParentheses()
        else -> false
    }
}

public fun JetExpression.getAssignmentByLHS(): JetBinaryExpression? {
    val parent = getParent() as? JetBinaryExpression ?: return null
    return if (JetPsiUtil.isAssignment(parent) && parent.getLeft() == this) parent else null
}

public fun JetExpression.isDotReceiver(): Boolean =
        (getParent() as? JetDotQualifiedExpression)?.getReceiverExpression() == this

public fun Call.isSafeCall(): Boolean {
    if (this is CallForImplicitInvoke) {
        //implicit safe 'invoke'
        if (getOuterCall().isExplicitSafeCall()) {
            return true
        }
    }
    return isExplicitSafeCall()
}

public fun Call.isExplicitSafeCall(): Boolean = getCallOperationNode()?.getElementType() == JetTokens.SAFE_ACCESS

public fun JetStringTemplateExpression.getContentRange(): TextRange {
    val start = getNode().getFirstChildNode().getTextLength()
    val lastChild = getNode().getLastChildNode()
    val length = getTextLength()
    return TextRange(start, if (lastChild.getElementType() == JetTokens.CLOSING_QUOTE) length - lastChild.getTextLength() else length)
}

public fun JetStringTemplateExpression.isSingleQuoted(): Boolean
        = getNode().getFirstChildNode().getTextLength() == 1

public fun JetNamedDeclaration.getValueParameters(): List<JetParameter> {
    return getValueParameterList()?.getParameters() ?: Collections.emptyList()
}

public fun JetNamedDeclaration.getValueParameterList(): JetParameterList? {
    return when (this) {
        is JetCallableDeclaration -> getValueParameterList()
        is JetClass -> getPrimaryConstructorParameterList()
        else -> null
    }
}

// Calls `block` on each descendant of T type
// Note, that calls happen in order of DFS-exit, so deeper nodes are applied earlier
public inline fun <reified T : JetElement> forEachDescendantOfTypeVisitor(noinline block: (T) -> Unit): JetVisitorVoid {
    return object : JetTreeVisitorVoid() {
        override fun visitJetElement(element: JetElement) {
            super.visitJetElement(element)
            if (element is T) {
                block(element)
            }
        }
    }
}

public inline fun <reified T : JetElement, R> flatMapDescendantsOfTypeVisitor(accumulator: MutableCollection<R>, noinline map: (T) -> Collection<R>): JetVisitorVoid {
    return forEachDescendantOfTypeVisitor<T> { accumulator.addAll(map(it)) }
}

public fun PsiFile.getFqNameByDirectory(): FqName {
    val qualifiedNameByDirectory = getParent()?.getPackage()?.getQualifiedName()
    return qualifiedNameByDirectory?.let { FqName(it) } ?: FqName.ROOT
}

public fun JetFile.packageMatchesDirectory(): Boolean = getPackageFqName() == getFqNameByDirectory()

public fun JetAnnotationsContainer.collectAnnotationEntriesFromStubOrPsi(): List<JetAnnotationEntry> =
    when (this) {
        is StubBasedPsiElementBase<*> -> getStub()?.collectAnnotationEntriesFromStubElement() ?: collectAnnotationEntriesFromPsi()
        else -> collectAnnotationEntriesFromPsi()
    }

private fun StubElement<*>.collectAnnotationEntriesFromStubElement() =
    getChildrenStubs().flatMap {
        child ->
        when (child.getStubType()) {
            JetNodeTypes.ANNOTATION_ENTRY -> listOf(child.getPsi() as JetAnnotationEntry)
            JetNodeTypes.ANNOTATION -> (child.getPsi() as JetAnnotation).getEntries()
            else -> emptyList<JetAnnotationEntry>()
        }
    }

private fun JetAnnotationsContainer.collectAnnotationEntriesFromPsi() =
    getChildren().flatMap {
        child ->
        when (child) {
            is JetAnnotationEntry -> listOf(child)
            is JetAnnotation -> child.getEntries()
            else -> emptyList<JetAnnotationEntry>()
        }
    }

public fun JetElement.getCalleeHighlightingRange(): TextRange {
    val annotationEntry: JetAnnotationEntry =
            PsiTreeUtil.getParentOfType<JetAnnotationEntry>(
                    this, javaClass<JetAnnotationEntry>(), /* strict = */false, javaClass<JetValueArgumentList>()
            ) ?: return getTextRange()

    val startOffset = annotationEntry.getAtSymbol()?.getTextRange()?.getStartOffset()
                      ?: annotationEntry.getCalleeExpression().startOffset

    return TextRange(startOffset, annotationEntry.getCalleeExpression().endOffset)
}

public fun JetBlockExpression.contentRange(): PsiChildRange {
    val first = (getLBrace()?.getNextSibling() ?: getFirstChild())
                        ?.siblings(withItself = false)
                        ?.firstOrNull { it !is PsiWhiteSpace }
    val rBrace = getRBrace()
    if (first == rBrace) return PsiChildRange.EMPTY
    val last = rBrace!!
            .siblings(forward = false, withItself = false)
            .first { it !is PsiWhiteSpace }
    return PsiChildRange(first, last)
}

// Annotations on labeled expression lies on it's base expression
public fun JetExpression.getAnnotationEntries(): List<JetAnnotationEntry> {
    val parent = getParent()
    return when (parent) {
        is JetAnnotatedExpression -> parent.getAnnotationEntries()
        is JetLabeledExpression -> parent.getAnnotationEntries()
        else -> emptyList<JetAnnotationEntry>()
    }
}

public fun JetElement.getQualifiedExpressionForSelector(): JetQualifiedExpression? {
    val parent = getParent()
    return if (parent is JetQualifiedExpression && parent.getSelectorExpression() == this) parent else null
}

public fun JetElement.getQualifiedExpressionForSelectorOrThis(): JetElement {
    return getQualifiedExpressionForSelector() ?: this
}
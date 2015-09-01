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

package org.jetbrains.kotlin.idea.quickfix.replaceWith

import com.intellij.openapi.util.Key
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.impl.LocalVariableDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.caches.resolve.resolveImportReference
import org.jetbrains.kotlin.idea.core.asExpression
import org.jetbrains.kotlin.idea.core.copied
import org.jetbrains.kotlin.idea.core.replaced
import org.jetbrains.kotlin.idea.imports.importableFqName
import org.jetbrains.kotlin.idea.intentions.InsertExplicitTypeArgumentsIntention
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.FqNameUnsafe
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.forEachDescendantOfType
import org.jetbrains.kotlin.psi.psiUtil.getReceiverExpression
import org.jetbrains.kotlin.resolve.*
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo
import org.jetbrains.kotlin.resolve.descriptorUtil.isExtension
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.lazy.FileScopeProvider
import org.jetbrains.kotlin.resolve.lazy.descriptors.ClassResolutionScopesSupport
import org.jetbrains.kotlin.resolve.scopes.*
import org.jetbrains.kotlin.resolve.scopes.receivers.ThisReceiver
import org.jetbrains.kotlin.resolve.scopes.utils.asJetScope
import org.jetbrains.kotlin.resolve.scopes.utils.asLexicalScope
import org.jetbrains.kotlin.storage.LockBasedStorageManager
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.expressions.ExpressionTypingServices
import org.jetbrains.kotlin.utils.addIfNotNull
import java.util.*

data class ReplaceWith(val expression: String, vararg val imports: String)

object ReplaceWithAnnotationAnalyzer {
    public val PARAMETER_USAGE_KEY: Key<Name> = Key("PARAMETER_USAGE")
    public val TYPE_PARAMETER_USAGE_KEY: Key<Name> = Key("TYPE_PARAMETER_USAGE")

    public data class ReplacementExpression(
            val expression: JetExpression,
            val fqNamesToImport: Collection<FqName>
    ) {
        fun copy() = ReplacementExpression(expression.copied(), fqNamesToImport)
    }

    public fun analyze(
            annotation: ReplaceWith,
            symbolDescriptor: CallableDescriptor,
            resolutionFacade: ResolutionFacade
    ): ReplacementExpression {
        val originalDescriptor = (if (symbolDescriptor is CallableMemberDescriptor)
            DescriptorUtils.unwrapFakeOverride(symbolDescriptor)
        else
            symbolDescriptor).original
        return analyzeOriginal(annotation, originalDescriptor, resolutionFacade)
    }

    private fun analyzeOriginal(
            annotation: ReplaceWith,
            symbolDescriptor: CallableDescriptor,
            resolutionFacade: ResolutionFacade
    ): ReplacementExpression {
        val psiFactory = JetPsiFactory(resolutionFacade.project)
        var expression = psiFactory.createExpression(annotation.expression)

        val importFqNames = annotation.imports
                .filter { FqNameUnsafe.isValid(it) }
                .map { FqNameUnsafe(it) }
                .filter { it.isSafe }
                .mapTo(LinkedHashSet<FqName>()) { it.toSafe() }

        val explicitlyImportedSymbols = importFqNames.flatMap { resolutionFacade.resolveImportReference(symbolDescriptor.module, it) }

        val additionalScopes = resolutionFacade.getFrontendService(FileScopeProvider.AdditionalScopes::class.java)
        val scope = getResolutionScope(symbolDescriptor, symbolDescriptor,
                                       listOf(ExplicitImportsScope(explicitlyImportedSymbols)) + additionalScopes.scopes)

        var bindingContext = analyzeInContext(expression, symbolDescriptor, scope, resolutionFacade)

        val typeArgsToAdd = ArrayList<Pair<JetCallExpression, JetTypeArgumentList>>()
        expression.forEachDescendantOfType<JetCallExpression> {
            if (InsertExplicitTypeArgumentsIntention.isApplicableTo(it, bindingContext)) {
                typeArgsToAdd.add(it to InsertExplicitTypeArgumentsIntention.createTypeArguments(it, bindingContext)!!)
            }
        }

        if (typeArgsToAdd.isNotEmpty()) {
            for ((callExpr, typeArgs) in typeArgsToAdd) {
                callExpr.addAfter(typeArgs, callExpr.calleeExpression)
            }

            // reanalyze expression - new usages of type parameters may be added
            bindingContext = analyzeInContext(expression, symbolDescriptor, scope, resolutionFacade)
        }

        val receiversToAdd = ArrayList<Pair<JetExpression, JetExpression>>()

        expression.forEachDescendantOfType<JetSimpleNameExpression> { expression ->
            val target = bindingContext[BindingContext.REFERENCE_TARGET, expression] ?: return@forEachDescendantOfType

            if (target.isExtension || expression.getReceiverExpression() == null) {
                importFqNames.addIfNotNull(target.importableFqName)
            }

            if (expression.getReceiverExpression() == null) {
                if (target is ValueParameterDescriptor && target.containingDeclaration == symbolDescriptor) {
                    expression.putCopyableUserData(PARAMETER_USAGE_KEY, target.name)
                }
                else if (target is TypeParameterDescriptor && target.containingDeclaration == symbolDescriptor) {
                    expression.putCopyableUserData(TYPE_PARAMETER_USAGE_KEY, target.name)
                }

                val resolvedCall = expression.getResolvedCall(bindingContext)
                if (resolvedCall != null && resolvedCall.status.isSuccess) {
                    val receiver = if (resolvedCall.resultingDescriptor.isExtension)
                        resolvedCall.extensionReceiver
                    else
                        resolvedCall.dispatchReceiver
                    if (receiver is ThisReceiver) {
                        val receiverExpression = receiver.asExpression(scope.asJetScope(), psiFactory)
                        if (receiverExpression != null) {
                            receiversToAdd.add(expression to receiverExpression)
                        }
                    }
                }
            }
        }

        // add receivers in reverse order because arguments of a call were processed after the callee's name
        for ((expr, receiverExpression) in receiversToAdd.reversed()) {
            val expressionToReplace = expr.parent as? JetCallExpression ?: expr
            val newExpr = expressionToReplace.replaced(psiFactory.createExpressionByPattern("$0.$1", receiverExpression, expressionToReplace))
            if (expressionToReplace == expression) {
                expression = newExpr
            }
        }

        return ReplacementExpression(expression, importFqNames)
    }

    private fun analyzeInContext(
            expression: JetExpression,
            symbolDescriptor: CallableDescriptor,
            scope: LexicalScope,
            resolutionFacade: ResolutionFacade
    ): BindingContext {
        val traceContext = BindingTraceContext()
        resolutionFacade.getFrontendService(symbolDescriptor.module, ExpressionTypingServices::class.java)
                .getTypeInfo(scope, expression, TypeUtils.NO_EXPECTED_TYPE, DataFlowInfo.EMPTY, traceContext, false)
        return traceContext.bindingContext
    }

    private fun getResolutionScope(descriptor: DeclarationDescriptor, ownerDescriptor: DeclarationDescriptor, additionalScopes: Collection<JetScope>): LexicalScope {
        return when (descriptor) {
            is PackageFragmentDescriptor -> {
                val moduleDescriptor = descriptor.containingDeclaration
                getResolutionScope(moduleDescriptor.getPackage(descriptor.fqName), ownerDescriptor, additionalScopes)
            }

            is PackageViewDescriptor ->
                ChainedScope(ownerDescriptor, "ReplaceWith resolution scope", descriptor.memberScope, *additionalScopes.toTypedArray()).asLexicalScope()

            is ClassDescriptorWithResolutionScopes ->
                descriptor.scopeForMemberDeclarationResolution

            is ClassDescriptor -> {
                val outerScope = getResolutionScope(descriptor.containingDeclaration, ownerDescriptor, additionalScopes)
                ClassResolutionScopesSupport(descriptor, LockBasedStorageManager.NO_LOCKS, { outerScope }).scopeForMemberDeclarationResolution()
            }

            is FunctionDescriptor ->
                FunctionDescriptorUtil.getFunctionInnerScope(getResolutionScope(descriptor.containingDeclaration, ownerDescriptor, additionalScopes),
                                                             descriptor, RedeclarationHandler.DO_NOTHING)

            is PropertyDescriptor ->
                JetScopeUtils.getPropertyDeclarationInnerScope(descriptor,
                                                               getResolutionScope(descriptor.containingDeclaration, ownerDescriptor, additionalScopes),
                                                               RedeclarationHandler.DO_NOTHING)
            is LocalVariableDescriptor -> {
                val declaration = DescriptorToSourceUtils.descriptorToDeclaration(descriptor) as JetDeclaration
                declaration.analyze()[BindingContext.RESOLUTION_SCOPE, declaration]!!.asLexicalScope()
            }

            //TODO?
            else -> throw IllegalArgumentException("Cannot find resolution scope for $descriptor")
        }
    }
}
/*
 * Copyright 2009 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.stream.Collectors.toList;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.SetMultimap;
import com.google.errorprone.annotations.DoNotCall;
import com.google.errorprone.annotations.Immutable;
import com.google.javascript.jscomp.CodingConvention.Cache;
import com.google.javascript.jscomp.NodeTraversal.Callback;
import com.google.javascript.jscomp.NodeTraversal.ScopedCallback;
import com.google.javascript.jscomp.OptimizeCalls.ReferenceMap;
import com.google.javascript.jscomp.graph.DiGraph;
import com.google.javascript.jscomp.graph.DiGraph.DiGraphNode;
import com.google.javascript.jscomp.graph.FixedPointGraphTraversal;
import com.google.javascript.jscomp.graph.LinkedDirectedGraph;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import com.google.javascript.rhino.jstype.FunctionType;
import com.google.javascript.rhino.jstype.JSType;
import com.google.javascript.rhino.jstype.JSTypeNative;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Compiler pass that computes function purity and annotates invocation nodes with those purities.
 *
 * <p>A function is pure if it has no outside visible side effects, and the result of the
 * computation does not depend on external factors that are beyond the control of the application;
 * repeated calls to the function should return the same value as long as global state hasn't
 * changed.
 *
 * <p>`Date.now` is an example of a function that has no side effects but is not pure.
 *
 * <p>Functions are not tracked individually but rather in aggregate by their name. This is because
 * it's impossible to determine exactly which function named "foo" is being called at a particular
 * site. Therefore, if <em>any</em> function "foo" has a particular side-effect, <em>all</em>
 * invocations "foo" are assumed to trigger it.
 *
 * <p>This pass could be greatly improved by proper tracking of locals within function bodies. Every
 * instance of the call to {@link NodeUtil#evaluatesToLocalValue(Node)} and {@link
 * NodeUtil#allArgsUnescapedLocal(Node)} do not actually take into account local variables. They
 * only assume literals, primitives, and operations on primitives are local.
 *
 * @author johnlenz@google.com (John Lenz)
 * @author tdeegan@google.com (Thomas Deegan)
 */
class PureFunctionIdentifier implements OptimizeCalls.CallGraphCompilerPass {

  // A prefix to differentiate property names from variable names.
  // TODO(nickreid): This pass could be made more efficient if props and variables were maintained
  // in separate datastructures. We wouldn't allocate a bunch of extra strings.
  private static final String PROP_NAME_PREFIX = ".";

  /**
   * Property names which are known to refer to functions that are too dynamic to analyze.
   *
   * <p>In particular, this includes functions which invoke other functions that they don't clearly
   * alias.
   *
   * <p>This is of interest primarily if these properties are themselves being aliased, not when
   * they are invoked. (i.e. `foo.bar = fn.call').
   *
   * <p>In some cases, like "call" and "apply", there may be special casing of how these functions
   * propagate side-effects. For example, it is not the case that every invocation `foo.call(this)`
   * is considered side-effectful.
   */
  private static final ImmutableSet<String> DYNAMIC_FUNCTION_PROPS =
      ImmutableSet.of(
          PROP_NAME_PREFIX + "call", //
          PROP_NAME_PREFIX + "apply",
          PROP_NAME_PREFIX + "constructor");

  private final AbstractCompiler compiler;

  /**
   * Map of function names to the summary of the functions with that name.
   *
   * <p>Variable names are recorded as-is. Property names are prefixed with {@link
   * #PROP_NAME_PREFIX} to differentiate them from variable names.
   *
   * @see {@link AmbiguatedFunctionSummary}
   */
  private final Map<String, AmbiguatedFunctionSummary> summariesByName = new HashMap<>();

  /**
   * Mapping from function node to side effects for all names associated with that node.
   *
   * <p>This is a multimap because you can construct situations in which a function node has
   * multiple names, and therefore multiple associated side-effect infos. For example:
   *
   * <pre>
   *   // Not enough type information to collapse/disambiguate properties on "staticMethod".
   *   SomeClass.staticMethod = function anotherName() {};
   *   OtherClass.staticMethod = function() { global++; }
   * </pre>
   *
   * <p>In this situation we want to keep the side effects for "staticMethod" which are "global"
   * separate from "anotherName". Hence the function node should point to the {@link
   * AmbiguatedFunctionSummary} for both "staticMethod" and "anotherName".
   *
   * <p>We could instead store a map of FUNCTION nodes to names, and then join that with the name of
   * names to infos. However, since names are 1:1 with infos, it's more effecient to "pre-join" in
   * this way.
   */
  private final Multimap<Node, AmbiguatedFunctionSummary> summariesForAllNamesOfFunctionByNode =
      ArrayListMultimap.create();

  // List of all function call sites; used to iterate in markPureFunctionCalls.
  private final List<Node> allFunctionCalls = new ArrayList<>();

  /**
   * A graph linking the summary of a function callee to the summaries of its callers.
   *
   * <p>Each node represents an aggregate summary of every function with a particular name. The edge
   * values indicate the details of the invocation necessary to propagate function impurity from
   * callee to caller.
   *
   * <p>Once all the invocation edges are in place, this graph will be traversed to transitively
   * propagate side-effect information across it's entire structure. The resultant side-effects can
   * then be attached to invocation sites.
   */
  private final LinkedDirectedGraph<AmbiguatedFunctionSummary, CallSitePropagationInfo>
      reverseCallGraph = LinkedDirectedGraph.createWithoutAnnotations();

  private boolean hasProcessed = false;

  public PureFunctionIdentifier(AbstractCompiler compiler) {
    this.compiler = checkNotNull(compiler);
  }

  @Override
  public void process(Node externs, Node root, ReferenceMap references) {
    checkState(compiler.getLifeCycleStage().isNormalized());
    checkState(
        !hasProcessed, "PureFunctionIdentifier::process may only be called once per instance.");
    this.hasProcessed = true;

    populateDatastructuresForAnalysisTraversal(references);

    NodeTraversal.traverse(compiler, externs, new ExternFunctionAnnotationAnalyzer());
    NodeTraversal.traverse(compiler, root, new FunctionBodyAnalyzer());

    propagateSideEffects();

    markPureFunctionCalls();
  }

  /**
   * Unwraps a complicated expression to reveal directly callable nodes that correspond to
   * definitions. For example: (a.c || b) or (x ? a.c : b) are turned into [a.c, b]. Since when you
   * call
   *
   * <pre>
   *   var result = (a.c || b)(some, parameters);
   * </pre>
   *
   * either a.c or b are called.
   *
   * @param exp A possibly complicated expression.
   * @return A list of GET_PROP NAME and function expression nodes (all of which can be called). Or
   *     null if any of the callable nodes are of an unsupported type. e.g. x['asdf'](param);
   */
  @Nullable
  private static ImmutableList<Node> unwrapCallableExpression(Node exp) {
    switch (exp.getToken()) {
      case GETPROP:
        if (isInvocationViaCallOrApply(exp.getParent())) {
          return unwrapCallableExpression(exp.getFirstChild());
        }
        return ImmutableList.of(exp);
      case FUNCTION:
      case NAME:
        return ImmutableList.of(exp);
      case OR:
      case HOOK:
        Node firstVal;
        if (exp.isHook()) {
          firstVal = exp.getSecondChild();
        } else {
          firstVal = exp.getFirstChild();
        }
        ImmutableList<Node> firstCallable = unwrapCallableExpression(firstVal);
        ImmutableList<Node> secondCallable = unwrapCallableExpression(firstVal.getNext());

        if (firstCallable == null || secondCallable == null) {
          return null;
        }
        return ImmutableList.<Node>builder().addAll(firstCallable).addAll(secondCallable).build();
      default:
        return null; // Unsupported call type.
    }
  }

  /**
   * Return {@code true} only if {@code rvalue} is defintely a reference reading a value.
   *
   * <p>For the most part it's sufficient to cover cases where a nominal function reference might
   * reasonably be expected, since those are the values that matter to analysis.
   *
   * <p>It's very important that this never returns {@code true} for an L-value, including when new
   * syntax is added to the language. That would cause some impure functions to be considered pure.
   * Therefore, this method is a very explict whitelist. Anything that's unrecognized is considered
   * not an R-value. This is insurance against new syntax.
   *
   * <p>New cases can be added as needed to increase the accuracy of the analysis. They just have to
   * be verified as always R-values.
   */
  private static boolean isDefinitelyRValue(Node rvalue) {
    Node parent = rvalue.getParent();

    switch (parent.getToken()) {
      case AND:
      case COMMA:
      case HOOK:
      case OR:
        // Function values pass through conditionals.
      case EQ:
      case NOT:
      case SHEQ:
        // Functions can be usefully compared for equality / existence.
      case ARRAYLIT:
      case CALL:
      case NEW:
      case TAGGED_TEMPLATELIT:
        // Functions are the callees and parameters of an invocation.
      case INSTANCEOF:
      case TYPEOF:
        // Often used to determine if a ctor/method exists/matches.
      case GETELEM:
      case GETPROP:
        // Many functions, especially ctors, have properties.
      case RETURN:
      case YIELD:
        // Higher order functions return functions.
        return true;

      case SWITCH:
      case CASE:
        // Delegating on the identity of a function.
      case IF:
      case WHILE:
        // Checking the existence of an optional function.
        return rvalue.isFirstChildOf(parent);

      case EXPR_RESULT:
        // Extern declarations are sometimes stubs. These must be considered L-values with no
        // associated R-values.
        return !rvalue.isFromExterns();

      case CLASS: // `extends` clause.
      case ASSIGN:
        return rvalue.isSecondChildOf(parent);

      case STRING_KEY: // Assignment to an object literal property. Excludes object destructuring.
        return parent.getParent().isObjectLit();

      default:
        // Anything not explicitly listed may not be an R-value. We only worry about the likely
        // cases for nominal function values since those are what interest us and its safe to miss
        // some R-values. It's more important that we correctly identify L-values.
        return false;
    }
  }

  private static boolean isSupportedFunctionDefinition(@Nullable Node definitionRValue) {
    if (definitionRValue == null) {
      return false;
    }
    switch (definitionRValue.getToken()) {
      case FUNCTION:
        return true;
      case HOOK:
        return isSupportedFunctionDefinition(definitionRValue.getSecondChild())
            && isSupportedFunctionDefinition(definitionRValue.getLastChild());
      default:
        return false;
    }
  }

  private ImmutableList<Node> getGoogCacheCallableExpression(Cache cacheCall) {
    checkNotNull(cacheCall);

    if (cacheCall.keyFn == null) {
      return unwrapCallableExpression(cacheCall.valueFn);
    }
    return ImmutableList.<Node>builder()
        .addAll(unwrapCallableExpression(cacheCall.valueFn))
        .addAll(unwrapCallableExpression(cacheCall.keyFn))
        .build();
  }

  @Nullable
  private List<AmbiguatedFunctionSummary> getSummariesForCallee(Node invocation) {
    checkArgument(NodeUtil.isInvocation(invocation), invocation);

    ImmutableList<Node> expanded;
    Cache cacheCall = compiler.getCodingConvention().describeCachingCall(invocation);
    if (cacheCall != null) {
      expanded = getGoogCacheCallableExpression(cacheCall);
    } else {
      expanded = unwrapCallableExpression(invocation.getFirstChild());
    }
    if (expanded == null) {
      return null;
    }
    List<AmbiguatedFunctionSummary> results = new ArrayList<>();
    for (Node expression : expanded) {
      if (NodeUtil.isFunctionExpression(expression)) {
        // isExtern is false in the call to the constructor for the
        // FunctionExpressionDefinition below because we know that
        // getFunctionDefinitions() will only be called on the first
        // child of an invocation and thus the function expression
        // definition will never be an extern.
        results.addAll(summariesForAllNamesOfFunctionByNode.get(expression));
        continue;
      }

      String name = nameForReference(expression);
      AmbiguatedFunctionSummary info = null;
      if (name != null) {
        info = summariesByName.get(name);
      }

      if (info != null) {
        results.add(info);
      } else {
        return null;
      }
    }
    return results;
  }

  /**
   * Fill all of the auxiliary data-structures used by this pass based on the results in {@code
   * referenceMap}.
   *
   * <p>This is the first step of analysis. These structures will be used by a traversal that
   * analyzes the bodies of located functions for side-effects. That traversal is separate because
   * it needs access to scopes and also depends on global knowledge of functions.
   */
  private void populateDatastructuresForAnalysisTraversal(ReferenceMap referenceMap) {
    // Merge the prop and name references into a single multimap since only the name matters.
    ArrayListMultimap<String, Node> referencesByName = ArrayListMultimap.create();
    for (Map.Entry<String, ? extends List<Node>> entry : referenceMap.getNameReferences()) {
      referencesByName.putAll(entry.getKey(), entry.getValue());
    }
    for (Map.Entry<String, ? extends List<Node>> entry : referenceMap.getPropReferences()) {
      referencesByName.putAll(PROP_NAME_PREFIX + entry.getKey(), entry.getValue());
    }
    // Empty function names cause a crash during analysis that is better to detect here.
    // Additionally, functions require a name to be invoked in a statically analyzable way; there's
    // no value in tracking the set of anonymous functions.
    checkState(!referencesByName.containsKey(""));
    checkState(!referencesByName.containsKey(PROP_NAME_PREFIX));

    // Create and store a summary for all known names.
    for (String name : referencesByName.keySet()) {
      summariesByName.put(name, AmbiguatedFunctionSummary.createInGraph(reverseCallGraph, name));
    }

    // Blacklist some highly dynamic names as definitely having side-effects.
    for (String prop : DYNAMIC_FUNCTION_PROPS) {
      summariesByName
          .computeIfAbsent(
              prop, (n) -> AmbiguatedFunctionSummary.createInGraph(reverseCallGraph, n))
          .setAllFlags();
    }

    Multimaps.asMap(referencesByName).forEach(this::populateFunctionDefinitions);
  }

  /**
   * For a name and its set of references, record the set of functions that may define that name or
   * blacklist the name if there are unclear definitions.
   *
   * @param name A variable or property name,
   * @param references The set of all nodes representing R- and L-value references to {@code name}.
   */
  private void populateFunctionDefinitions(String name, List<Node> references) {
    AmbiguatedFunctionSummary summaryForName = checkNotNull(summariesByName.get(name));

    // Make sure we get absolutely every R-value assigned to `name` or at the very least detect
    // there are some we're missing. Overlooking a single R-value would invalidate the analysis.
    List<ImmutableList<Node>> rvaluesAssignedToName =
        references.stream()
            // Eliminate any references that we're sure are R-values themselves. Otherwise
            // there's a high probability we'll inspect an R-value for futher R-values. We wouldn't
            // find any, and then we'd have to consider `name` impure.
            .filter((n) -> !isDefinitelyRValue(n))
            // For anything that might be an L-reference, get the expression being assigned to it.
            .map(NodeUtil::getRValueOfLValue)
            // If the assigned R-value is an analyzable expression, collect all the possible
            // FUNCTIONs that could result from that expression. If the expression isn't analyzable,
            // represent that with `null` so we can blacklist `name`.
            .map((n) -> isSupportedFunctionDefinition(n) ? unwrapCallableExpression(n) : null)
            .collect(toList());

    if (rvaluesAssignedToName.isEmpty() || rvaluesAssignedToName.contains(null)) {
      // Any of:
      // - There are no L-values with this name.
      // - There's a an L-value and we can't find the associated R-values.
      // - There's a an L-value with R-values are not all known to be callable.
      summaryForName.setAllFlags();
    } else {
      rvaluesAssignedToName.stream()
          .flatMap(List::stream)
          .forEach(
              (f) -> {
                checkState(f.isFunction(), f);
                summariesForAllNamesOfFunctionByNode.put(f, summaryForName);
              });
    }
  }

  /**
   * Propagate side effect information in {@link #reverseCallGraph} from callees to callers.
   *
   * <p>This is an iterative process executed until a fixed point, where no caller summary would be
   * given new side-effects from from any callee summary, is reached.
   */
  private void propagateSideEffects() {
    FixedPointGraphTraversal.newTraversal(
            (AmbiguatedFunctionSummary source,
                CallSitePropagationInfo edge,
                AmbiguatedFunctionSummary destination) -> edge.propagate(source, destination))
        .computeFixedPoint(reverseCallGraph);
  }

  /** Set no side effect property at pure-function call sites. */
  private void markPureFunctionCalls() {
    for (Node callNode : allFunctionCalls) {
      List<AmbiguatedFunctionSummary> calleeSummaries = getSummariesForCallee(callNode);
      // Default to side effects, non-local results
      Node.SideEffectFlags flags = new Node.SideEffectFlags();
      if (calleeSummaries == null) {
        flags.setMutatesGlobalState();
        flags.setThrows();
        flags.setReturnsTainted();
      } else {
        flags.clearAllFlags();
        for (AmbiguatedFunctionSummary calleeSummary : calleeSummaries) {
          checkNotNull(calleeSummary);
          if (calleeSummary.mutatesGlobalState()) {
            flags.setMutatesGlobalState();
          }

          if (calleeSummary.mutatesArguments()) {
            flags.setMutatesArguments();
          }

          if (calleeSummary.functionThrows()) {
            flags.setThrows();
          }

          if (isCallOrTaggedTemplateLit(callNode)) {
            if (calleeSummary.mutatesThis()) {
              // A FunctionInfo for "f" maps to both "f()" and "f.call()" nodes.
              if (isInvocationViaCallOrApply(callNode)) {
                flags.setMutatesArguments(); // `this` is actually an argument.
              } else {
                flags.setMutatesThis();
              }
            }
          }

          if (calleeSummary.escapedReturn()) {
            flags.setReturnsTainted();
          }
        }
      }

      // Handle special cases (Math, RegExp)
      if (isCallOrTaggedTemplateLit(callNode)) {
        if (!NodeUtil.functionCallHasSideEffects(callNode, compiler)) {
          flags.clearSideEffectFlags();
        }
      } else if (callNode.isNew()) {
        // Handle known cases now (Object, Date, RegExp, etc)
        if (!NodeUtil.constructorCallHasSideEffects(callNode)) {
          flags.clearSideEffectFlags();
        }
      }

      int newSideEffectFlags = flags.valueOf();
      if (callNode.getSideEffectFlags() != newSideEffectFlags) {
        callNode.setSideEffectFlags(newSideEffectFlags);
        compiler.reportChangeToEnclosingScope(callNode);
      }
    }
  }

  /**
   * Inspects function JSDoc for side effects and applies them to the associated {@link
   * AmbiguatedFunctionSummary}.
   *
   * <p>This callback is only meant for use on externs.
   */
  private final class ExternFunctionAnnotationAnalyzer implements Callback {
    @Override
    public boolean shouldTraverse(NodeTraversal traversal, Node node, Node parent) {
      return true;
    }

    @Override
    public void visit(NodeTraversal traversal, Node node, Node parent) {
      if (!node.isFunction()) {
        return;
      }

      for (AmbiguatedFunctionSummary definitionSummary :
          summariesForAllNamesOfFunctionByNode.get(node)) {
        updateSideEffectsForExternFunction(node, definitionSummary);
      }
    }

    /** Update function for @nosideeffects annotations. */
    private void updateSideEffectsForExternFunction(
        Node externFunction, AmbiguatedFunctionSummary summary) {
      checkArgument(externFunction.isFunction());
      checkArgument(externFunction.isFromExterns());

      JSDocInfo info = NodeUtil.getBestJSDocInfo(externFunction);
      // Handle externs.
      JSType typei = externFunction.getJSType();
      FunctionType functionType = typei == null ? null : typei.toMaybeFunctionType();
      if (functionType == null) {
        // Assume extern functions return tainted values when we have no type info to say otherwise.
        summary.setEscapedReturn();
      } else {
        JSType retType = functionType.getReturnType();
        if (!isLocalValueType(retType, compiler)) {
          summary.setEscapedReturn();
        }
      }

      if (info == null) {
        // We don't know anything about this function so we assume it has side effects.
        summary.setMutatesGlobalState();
        summary.setFunctionThrows();
      } else {
        if (info.modifiesThis()) {
          summary.setMutatesThis();
        } else if (info.hasSideEffectsArgumentsAnnotation()) {
          summary.setMutatesArguments();
        } else if (!info.getThrownTypes().isEmpty()) {
          summary.setFunctionThrows();
        } else if (info.isNoSideEffects()) {
          // Do nothing.
        } else {
          summary.setMutatesGlobalState();
        }
      }
    }

    /**
     * Return whether {@code type} is guaranteed to be a that of a "local value".
     *
     * <p>For the purposes of purity analysis we really only care whether a return value is
     * immutable and identity-less; such values can't contribute to side-effects. Therefore, this
     * method is implemented to check if {@code type} is that of a primitive, since primitives
     * exhibit both relevant behaviours.
     */
    private boolean isLocalValueType(JSType typei, AbstractCompiler compiler) {
      checkNotNull(typei);
      JSType nativeObj = compiler.getTypeRegistry().getNativeType(JSTypeNative.OBJECT_TYPE);
      JSType subtype = typei.meetWith(nativeObj);
      // If the type includes anything related to a object type, don't assume
      // anything about the locality of the value.
      return subtype.isEmptyType();
    }
  }

  private static final Predicate<Node> RHS_IS_ALWAYS_LOCAL = lhs -> true;
  private static final Predicate<Node> RHS_IS_NEVER_LOCAL = lhs -> false;
  private static final Predicate<Node> FIND_RHS_AND_CHECK_FOR_LOCAL_VALUE = lhs -> {
    Node rhs = NodeUtil.getRValueOfLValue(lhs);
    return rhs == null || NodeUtil.evaluatesToLocalValue(rhs);
  };

  /**
   * Inspects function bodies for side effects and applies them to the associated {@link
   * AmbiguatedFunctionSummary}.
   *
   * <p>This callback also fills {@link #allFunctionCalls}
   */
  private final class FunctionBodyAnalyzer implements ScopedCallback {
    private final SetMultimap<Node, Var> blacklistedVarsByFunction = HashMultimap.create();
    private final SetMultimap<Node, Var> taintedVarsByFunction = HashMultimap.create();

    @Override
    public boolean shouldTraverse(NodeTraversal traversal, Node node, Node parent) {
      if (!node.isFunction()) {
        return true;
      }

      // Functions need to be processed as part of pre-traversal so that an entry for the function
      // exists in the summariesForAllNamesOfFunctionByNode map when processing assignments and
      // calls within the body.

      if (!summariesForAllNamesOfFunctionByNode.containsKey(node)) {
        // This function was not part of a definition which is why it was not created by
        // {@link populateDatastructuresForAnalysisTraversal}. For example, an anonymous function.
        AmbiguatedFunctionSummary summary =
            AmbiguatedFunctionSummary.createInGraph(reverseCallGraph, "<anonymous>");
        summariesForAllNamesOfFunctionByNode.put(node, summary);
      }

      return true;
    }

    @Override
    public void visit(NodeTraversal traversal, Node node, Node parent) {
      if (!NodeUtil.nodeTypeMayHaveSideEffects(node, compiler) && !node.isReturn()) {
        return;
      }

      if (NodeUtil.isInvocation(node)) {
        // We collect these after filtering for side-effects because there's no point re-processing
        // a known pure call. This analysis is run multiple times, but no optimization will make a
        // pure function impure.
        allFunctionCalls.add(node);
      }

      Scope containerScope = traversal.getScope().getClosestContainerScope();
      if (!containerScope.isFunctionScope()) {
        // We only need to look at nodes in function scopes.
        return;
      }
      Node enclosingFunction = containerScope.getRootNode();

      for (AmbiguatedFunctionSummary encloserSummary :
          summariesForAllNamesOfFunctionByNode.get(enclosingFunction)) {
        checkNotNull(encloserSummary);
        updateSideEffectsForNode(encloserSummary, traversal, node, enclosingFunction);
      }
    }

    public void updateSideEffectsForNode(
        AmbiguatedFunctionSummary encloserSummary,
        NodeTraversal traversal,
        Node node,
        Node enclosingFunction) {

      switch (node.getToken()) {
        case ASSIGN:
          // e.g.
          // lhs = rhs;
          // ({x, y} = object);
          visitLhsNodes(
              encloserSummary,
              traversal.getScope(),
              enclosingFunction,
              NodeUtil.findLhsNodesInNode(node),
              FIND_RHS_AND_CHECK_FOR_LOCAL_VALUE);
          break;

        case INC: // e.g. x++;
        case DEC:
        case DELPROP:
          visitLhsNodes(
              encloserSummary,
              traversal.getScope(),
              enclosingFunction,
              ImmutableList.of(node.getOnlyChild()),
              // The value assigned by a unary op is always local.
              RHS_IS_ALWAYS_LOCAL);
          break;

        case FOR_AWAIT_OF:
          setSideEffectsForControlLoss(encloserSummary); // Control is lost while awaiting.
          // Fall through.
        case FOR_OF:
          // e.g.
          // for (const {prop1, prop2} of iterable) {...}
          // for ({prop1: x.p1, prop2: x.p2} of iterable) {...}
          //
          // TODO(bradfordcsmith): Possibly we should try to determine whether the iteration itself
          //     could have side effects.
          visitLhsNodes(
              encloserSummary,
              traversal.getScope(),
              enclosingFunction,
              NodeUtil.findLhsNodesInNode(node),
              // The RHS of a for-of must always be an iterable, making it a container, so we can't
              // consider its contents to be local
              RHS_IS_NEVER_LOCAL);
          checkIteratesImpureIterable(node, encloserSummary);
          break;

        case FOR_IN:
          // e.g.
          // for (prop in obj) {...}
          // Also this, though not very useful or readable.
          // for ([char1, char2, ...x.rest] in obj) {...}
          visitLhsNodes(
              encloserSummary,
              traversal.getScope(),
              enclosingFunction,
              NodeUtil.findLhsNodesInNode(node),
              // A for-in always assigns a string, which is a local value by definition.
              RHS_IS_ALWAYS_LOCAL);
          break;

        case CALL:
        case NEW:
        case TAGGED_TEMPLATELIT:
          visitCall(encloserSummary, node);
          break;

        case NAME:
          // Variable definition are not side effects. Check that the name appears in the context of
          // a variable declaration.
          checkArgument(NodeUtil.isNameDeclaration(node.getParent()), node.getParent());
          Node value = node.getFirstChild();
          // Assignment to local, if the value isn't a safe local value,
          // new object creation or literal or known primitive result
          // value, add it to the local blacklist.
          if (value != null && !NodeUtil.evaluatesToLocalValue(value)) {
            Scope scope = traversal.getScope();
            Var var = scope.getVar(node.getString());
            blacklistedVarsByFunction.put(enclosingFunction, var);
          }
          break;

        case THROW:
          encloserSummary.setFunctionThrows();
          break;

        case RETURN:
          if (node.hasChildren() && !NodeUtil.evaluatesToLocalValue(node.getFirstChild())) {
            encloserSummary.setEscapedReturn();
          }
          break;

        case YIELD:
          checkIteratesImpureIterable(node, encloserSummary); // `yield*` triggers iteration.
          // 'yield' throws if the caller calls `.throw` on the generator object.
          setSideEffectsForControlLoss(encloserSummary);
          break;

        case AWAIT:
          // 'await' throws if the promise it's waiting on is rejected.
          setSideEffectsForControlLoss(encloserSummary);
          break;

        case REST:
        case SPREAD:
          checkIteratesImpureIterable(node, encloserSummary);
          break;

        default:
          if (NodeUtil.isCompoundAssignmentOp(node)) {
            // e.g.
            // x += 3;
            visitLhsNodes(
                encloserSummary,
                traversal.getScope(),
                enclosingFunction,
                ImmutableList.of(node.getFirstChild()),
                // The update assignments (e.g. `+=) always assign primitive, and therefore local,
                // values.
                RHS_IS_ALWAYS_LOCAL);
            break;
          }

          throw new IllegalArgumentException("Unhandled side effect node type " + node);
      }
    }

    /**
     * Inspect {@code node} for impure iteration and assign the appropriate side-effects to {@code
     * encloserSummary} if so.
     */
    private void checkIteratesImpureIterable(Node node, AmbiguatedFunctionSummary encloserSummary) {
      if (!NodeUtil.iteratesImpureIterable(node)) {
        return;
      }

      // Treat the (possibly implicit) call to `iterator.next()` as having the same effects as any
      // other unknown function call.
      encloserSummary.setFunctionThrows();
      encloserSummary.setMutatesGlobalState();

      // The iterable may be stateful and a param.
      encloserSummary.setMutatesArguments();
    }

    /**
     * Assigns the set of side-effects associated with an arbitrary loss of control flow to {@code
     * encloserSummary}.
     */
    private void setSideEffectsForControlLoss(AmbiguatedFunctionSummary encloserSummary) {
      encloserSummary.setFunctionThrows();
    }

    @Override
    public void enterScope(NodeTraversal t) {
      // Nothing to do.
    }

    @Override
    public void exitScope(NodeTraversal t) {
      Scope closestContainerScope = t.getScope().getClosestContainerScope();
      if (!closestContainerScope.isFunctionScope()) {
        // Only functions and the scopes within them are of interest to us.
        return;
      }
      Node function = closestContainerScope.getRootNode();

      // Handle deferred local variable modifications:
      for (AmbiguatedFunctionSummary sideEffectInfo :
          summariesForAllNamesOfFunctionByNode.get(function)) {
        checkNotNull(sideEffectInfo, "%s has no side effect info.", function);

        if (sideEffectInfo.mutatesGlobalState()) {
          continue;
        }

        for (Var v : t.getScope().getVarIterable()) {
          if (v.isParam()
              && !blacklistedVarsByFunction.containsEntry(function, v)
              && taintedVarsByFunction.containsEntry(function, v)) {
            sideEffectInfo.setMutatesArguments();
            continue;
          }

          boolean localVar = false;
          // Parameters and catch values can come from other scopes.
          if (!v.isParam() && !v.isCatch()) {
            // TODO(johnlenz): create a useful parameter list
            // sideEffectInfo.addKnownLocal(v.getName());
            localVar = true;
          }

          // Take care of locals that might have been tainted.
          if (!localVar || blacklistedVarsByFunction.containsEntry(function, v)) {
            if (taintedVarsByFunction.containsEntry(function, v)) {
              // If the function has global side-effects
              // don't bother with the local side-effects.
              sideEffectInfo.setMutatesGlobalState();
              break;
            }
          }
        }
      }

      // Clean up memory after exiting out of the function scope where we will no longer need these.
      if (t.getScopeRoot().isFunction()) {
        blacklistedVarsByFunction.removeAll(function);
        taintedVarsByFunction.removeAll(function);
      }
    }

    private boolean isVarDeclaredInSameContainerScope(@Nullable Var v, Scope scope) {
      return v != null && v.scope.hasSameContainerScope(scope);
    }

    /**
     * Record information about the side effects caused by assigning a value to a given LHS.
     *
     * <p>If the operation modifies this or taints global state, mark the enclosing function as
     * having those side effects.
     *
     * @param sideEffectInfo Function side effect record to be updated
     * @param scope variable scope in which the variable assignment occurs
     * @param enclosingFunction FUNCTION node for the enclosing function
     * @param lhsNodes LHS nodes that are all assigned values by a given parent node
     * @param hasLocalRhs Predicate indicating whether a given LHS is being assigned a local value
     */
    private void visitLhsNodes(
        AmbiguatedFunctionSummary sideEffectInfo,
        Scope scope,
        Node enclosingFunction,
        List<Node> lhsNodes,
        Predicate<Node> hasLocalRhs) {
      for (Node lhs : lhsNodes) {
        if (NodeUtil.isGet(lhs)) {
          if (lhs.getFirstChild().isThis()) {
            sideEffectInfo.setMutatesThis();
          } else {
            Node objectNode = lhs.getFirstChild();
            if (objectNode.isName()) {
              Var var = scope.getVar(objectNode.getString());
              if (isVarDeclaredInSameContainerScope(var, scope)) {
                // Maybe a local object modification.  We won't know for sure until
                // we exit the scope and can validate the value of the local.
                taintedVarsByFunction.put(enclosingFunction, var);
              } else {
                sideEffectInfo.setMutatesGlobalState();
              }
            } else {
              // Don't track multi level locals: local.prop.prop2++;
              sideEffectInfo.setMutatesGlobalState();
            }
          }
        } else {
          checkState(lhs.isName(), lhs);
          Var var = scope.getVar(lhs.getString());
          if (isVarDeclaredInSameContainerScope(var, scope)) {
            if (!hasLocalRhs.test(lhs)) {
              // Assigned value is not guaranteed to be a local value,
              // so if we see any property assignments on this variable,
              // they could be tainting a non-local value.
              blacklistedVarsByFunction.put(enclosingFunction, var);
            }
          } else {
            sideEffectInfo.setMutatesGlobalState();
          }
        }
      }
    }

    /** Record information about a call site. */
    private void visitCall(AmbiguatedFunctionSummary callerInfo, Node invocation) {
      // Handle special cases (Math, RegExp)
      // TODO: This logic can probably be replaced with @nosideeffects annotations in externs.
      if (invocation.isCall() && !NodeUtil.functionCallHasSideEffects(invocation, compiler)) {
        return;
      }

      // Handle known cases now (Object, Date, RegExp, etc)
      if (invocation.isNew() && !NodeUtil.constructorCallHasSideEffects(invocation)) {
        return;
      }

      List<AmbiguatedFunctionSummary> calleeSummaries = getSummariesForCallee(invocation);
      if (calleeSummaries == null) {
        callerInfo.setMutatesGlobalState();
        callerInfo.setFunctionThrows();
        return;
      }

      for (AmbiguatedFunctionSummary calleeInfo : calleeSummaries) {
        CallSitePropagationInfo edge = CallSitePropagationInfo.computePropagationType(invocation);
        reverseCallGraph.connect(calleeInfo.graphNode, edge, callerInfo.graphNode);
      }
    }
  }

  private static boolean isInvocationViaCallOrApply(Node callSite) {
    return NodeUtil.isFunctionObjectCall(callSite) || NodeUtil.isFunctionObjectApply(callSite);
  }

  private static boolean isCallOrTaggedTemplateLit(Node invocation) {
    return invocation.isCall() || invocation.isTaggedTemplateLit();
  }

  /**
   * Returns the unqualified name associated with an R-value.
   *
   * <p>For NAMEs this is the name. For GETPROPs this is the last segment including a leading dot.
   */
  @Nullable
  private static String nameForReference(Node nameRef) {
    switch (nameRef.getToken()) {
      case NAME:
        return nameRef.getString();
      case GETPROP:
        return PROP_NAME_PREFIX + nameRef.getSecondChild().getString();
      default:
        throw new IllegalStateException("Unexpected name reference: " + nameRef.toStringTree());
    }
  }

  /**
   * This class stores all the information about a call site needed to propagate side effects from
   * one instance of {@link AmbiguatedFunctionSummary} to another.
   */
  @Immutable
  private static class CallSitePropagationInfo {

    private CallSitePropagationInfo(
        boolean allArgsUnescapedLocal, boolean calleeThisEqualsCallerThis, Token callType) {
      checkArgument(NodeUtil.isInvocation(new Node(callType)), callType);
      this.allArgsUnescapedLocal = allArgsUnescapedLocal;
      this.calleeThisEqualsCallerThis = calleeThisEqualsCallerThis;
      this.callType = callType;
    }

    // If all the arguments values are local to the scope in which the call site occurs.
    private final boolean allArgsUnescapedLocal;
    /**
     * If you call a function with apply or call, one of the arguments at the call site will be used
     * as 'this' inside the implementation. If this is pass into apply like so: function.apply(this,
     * ...) then 'this' in the caller is tainted.
     */
    private final boolean calleeThisEqualsCallerThis;
    // Whether this represents CALL (not a NEW node).
    private final Token callType;

    /**
     * Propagate the side effects from the callee to the caller.
     *
     * @param callee propagate from
     * @param caller propagate to
     * @return Returns true if the propagation changed the side effects on the caller.
     */
    boolean propagate(AmbiguatedFunctionSummary callee, AmbiguatedFunctionSummary caller) {
      CallSitePropagationInfo propagationType = this;
      boolean changed = false;
      // If the callee modifies global state then so does that caller.
      if (callee.mutatesGlobalState() && !caller.mutatesGlobalState()) {
        caller.setMutatesGlobalState();
        changed = true;
      }
      // If the callee throws an exception then so does the caller.
      if (callee.functionThrows() && !caller.functionThrows()) {
        caller.setFunctionThrows();
        changed = true;
      }
      // If the callee mutates its input arguments and the arguments escape the caller then it has
      // unbounded side effects.
      if (callee.mutatesArguments()
          && !propagationType.allArgsUnescapedLocal
          && !caller.mutatesGlobalState()) {
        caller.setMutatesGlobalState();
        changed = true;
      }
      if (callee.mutatesThis() && propagationType.calleeThisEqualsCallerThis) {
        if (!caller.mutatesThis()) {
          caller.setMutatesThis();
          changed = true;
        }
      } else if (callee.mutatesThis() && propagationType.callType != Token.NEW) {
        // NEW invocations of a constructor that modifies "this" don't cause side effects.
        if (!caller.mutatesGlobalState()) {
          caller.setMutatesGlobalState();
          changed = true;
        }
      }
      return changed;
    }

    static CallSitePropagationInfo computePropagationType(Node callSite) {
      checkArgument(NodeUtil.isInvocation(callSite), callSite);

      boolean thisIsOuterThis = false;
      if (isCallOrTaggedTemplateLit(callSite)) {
        // Side effects only propagate via regular calls.
        // Calling a constructor that modifies "this" has no side effects.
        // Notice that we're using "mutatesThis" from the callee
        // summary. If the call site is actually a .call or .apply, then
        // the "this" is going to be one of its arguments.
        boolean isInvocationViaCallOrApply = isInvocationViaCallOrApply(callSite);
        Node objectNode =
            isInvocationViaCallOrApply ? callSite.getSecondChild() : callSite.getFirstFirstChild();
        if (objectNode != null && objectNode.isName() && !isInvocationViaCallOrApply) {
          // Exclude ".call" and ".apply" as the value may still be
          // null or undefined. We don't need to worry about this with a
          // direct method call because null and undefined don't have any
          // properties.

          // TODO(nicksantos): Turn this back on when locals-tracking
          // is fixed. See testLocalizedSideEffects11.
          //if (!caller.knownLocals.contains(name)) {
          //}
        } else if (objectNode != null && objectNode.isThis()) {
          thisIsOuterThis = true;
        }
      }

      boolean argsUnescapedLocal = NodeUtil.allArgsUnescapedLocal(callSite);
      return new CallSitePropagationInfo(argsUnescapedLocal, thisIsOuterThis, callSite.getToken());
    }
  }

  /**
   * A summary for the set of functions that share a particular name.
   *
   * <p>Side-effects of the functions are the most significant aspect of this summary. Because the
   * functions are "ambiguated", the recorded side-effects are the union of all side effects
   * detected in any member of the set.
   *
   * <p>Name in this context refers to a short name, not a qualified name; only the last segment of
   * a qualified name is used.
   */
  private static final class AmbiguatedFunctionSummary {

    // Side effect types:
    private static final int THROWS = 1 << 1;
    private static final int MUTATES_GLOBAL_STATE = 1 << 2;
    private static final int MUTATES_THIS = 1 << 3;
    private static final int MUTATES_ARGUMENTS = 1 << 4;
    // Function metatdata
    private static final int ESCAPED_RETURN = 1 << 5;

    // The name shared by the set of functions that defined this summary.
    private final String name;
    // The node holding this summary in the reverse call graph.
    private final DiGraphNode<AmbiguatedFunctionSummary, CallSitePropagationInfo> graphNode;
    // The side effect flags for this set of functions.
    // TODO(nickreid): Replace this with a `Node.SideEffectFlags`.
    private int bitmask = 0;

    /** Adds a new summary node to {@code graph}, storing the node and returning the summary. */
    static AmbiguatedFunctionSummary createInGraph(
        DiGraph<AmbiguatedFunctionSummary, CallSitePropagationInfo> graph, String name) {
      return new AmbiguatedFunctionSummary(graph, name);
    }

    private AmbiguatedFunctionSummary(
        DiGraph<AmbiguatedFunctionSummary, CallSitePropagationInfo> graph, String name) {
      this.name = checkNotNull(name);
      this.graphNode = graph.createDirectedGraphNode(this);
    }

    private void setMask(int mask) {
      bitmask |= mask;
    }

    private boolean getMask(int mask) {
      return (bitmask & mask) != 0;
    }

    boolean mutatesThis() {
      return getMask(MUTATES_THIS);
    }

    /** Marks the function as having "modifies this" side effects. */
    void setMutatesThis() {
      setMask(MUTATES_THIS);
    }

    /**
     * Returns whether the function returns something that is not affected by global state.
     *
     * <p>In the current implementation, this is only true if the return value is a literal or
     * primitive since locals are not tracked correctly.
     */
    boolean escapedReturn() {
      return getMask(ESCAPED_RETURN);
    }

    /** Marks the function as having non-local return result. */
    void setEscapedReturn() {
      setMask(ESCAPED_RETURN);
    }

    /** Returns true if function has an explicit "throw". */
    boolean functionThrows() {
      return getMask(THROWS);
    }

    /** Marks the function as having "throw" side effects. */
    void setFunctionThrows() {
      setMask(THROWS);
    }

    /** Returns true if function mutates global state. */
    boolean mutatesGlobalState() {
      return getMask(MUTATES_GLOBAL_STATE);
    }

    /** Marks the function as having "modifies globals" side effects. */
    void setMutatesGlobalState() {
      setMask(MUTATES_GLOBAL_STATE);
    }

    /** Returns true if function mutates its arguments. */
    boolean mutatesArguments() {
      return getMask(MUTATES_GLOBAL_STATE | MUTATES_ARGUMENTS);
    }

    /** Marks the function as having "modifies arguments" side effects. */
    void setMutatesArguments() {
      setMask(MUTATES_ARGUMENTS);
    }

    AmbiguatedFunctionSummary setAllFlags() {
      setMask(THROWS | MUTATES_THIS | MUTATES_ARGUMENTS | MUTATES_GLOBAL_STATE | ESCAPED_RETURN);
      return this;
    }

    @Override
    @DoNotCall // For debugging only.
    public String toString() {
      return MoreObjects.toStringHelper(getClass())
          .add("name", name)
          // Passing `graphNode` directly causes recursion as its `toString` calls `toString` on the
          // summary it contains.
          .add("graphNode", graphNode.hashCode())
          .add("sideEffects", sideEffectsToString())
          .toString();
    }

    private String sideEffectsToString() {
      List<String> status = new ArrayList<>();
      if (mutatesThis()) {
        status.add("this");
      }

      if (mutatesGlobalState()) {
        status.add("global");
      }

      if (mutatesArguments()) {
        status.add("args");
      }

      if (escapedReturn()) {
        status.add("return");
      }

      if (functionThrows()) {
        status.add("throw");
      }

      return status.toString();
    }
  }

  /**
   * A compiler pass that constructs a reference graph and drives the PureFunctionIdentifier across
   * it.
   */
  static class Driver implements CompilerPass {
    private final AbstractCompiler compiler;

    Driver(AbstractCompiler compiler) {
      this.compiler = compiler;
    }

    @Override
    public void process(Node externs, Node root) {
      OptimizeCalls.builder()
          .setCompiler(compiler)
          .setConsiderExterns(true)
          .addPass(new PureFunctionIdentifier(compiler))
          .build()
          .process(externs, root);
    }
  }
}

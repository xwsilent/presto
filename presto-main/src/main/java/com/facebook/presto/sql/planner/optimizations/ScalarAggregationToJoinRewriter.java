/*
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
package com.facebook.presto.sql.planner.optimizations;

import com.facebook.presto.metadata.FunctionRegistry;
import com.facebook.presto.metadata.Signature;
import com.facebook.presto.spi.type.BigintType;
import com.facebook.presto.spi.type.BooleanType;
import com.facebook.presto.spi.type.TypeSignature;
import com.facebook.presto.sql.ExpressionUtils;
import com.facebook.presto.sql.planner.DependencyExtractor;
import com.facebook.presto.sql.planner.PlanNodeIdAllocator;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.SymbolAllocator;
import com.facebook.presto.sql.planner.iterative.Lookup;
import com.facebook.presto.sql.planner.plan.AggregationNode;
import com.facebook.presto.sql.planner.plan.AggregationNode.Aggregation;
import com.facebook.presto.sql.planner.plan.AssignUniqueId;
import com.facebook.presto.sql.planner.plan.Assignments;
import com.facebook.presto.sql.planner.plan.EnforceSingleRowNode;
import com.facebook.presto.sql.planner.plan.FilterNode;
import com.facebook.presto.sql.planner.plan.JoinNode;
import com.facebook.presto.sql.planner.plan.LateralJoinNode;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.ProjectNode;
import com.facebook.presto.sql.planner.plan.SimplePlanRewriter;
import com.facebook.presto.sql.tree.DefaultTraversalVisitor;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.FunctionCall;
import com.facebook.presto.sql.tree.LogicalBinaryExpression;
import com.facebook.presto.sql.tree.QualifiedName;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.facebook.presto.sql.analyzer.TypeSignatureProvider.fromTypeSignatures;
import static com.facebook.presto.sql.planner.optimizations.PlanNodeSearcher.searchFrom;
import static com.facebook.presto.sql.planner.optimizations.Predicates.isInstanceOfAny;
import static com.facebook.presto.sql.planner.plan.SimplePlanRewriter.rewriteWith;
import static com.facebook.presto.sql.tree.BooleanLiteral.TRUE_LITERAL;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

// TODO: move this class to TransformCorrelatedScalarAggregationToJoin when old optimizer is gone
public class ScalarAggregationToJoinRewriter
{
    private static final QualifiedName COUNT = QualifiedName.of("count");

    private final FunctionRegistry functionRegistry;
    private final SymbolAllocator symbolAllocator;
    private final PlanNodeIdAllocator idAllocator;
    private final Lookup lookup;

    public ScalarAggregationToJoinRewriter(FunctionRegistry functionRegistry, SymbolAllocator symbolAllocator, PlanNodeIdAllocator idAllocator, Lookup lookup)
    {
        this.functionRegistry = requireNonNull(functionRegistry, "metadata is null");
        this.symbolAllocator = requireNonNull(symbolAllocator, "symbolAllocator is null");
        this.idAllocator = requireNonNull(idAllocator, "idAllocator is null");
        this.lookup = requireNonNull(lookup, "lookup is null");
    }

    public PlanNode rewriteScalarAggregation(LateralJoinNode lateralJoinNode, AggregationNode aggregation)
    {
        List<Symbol> correlation = lateralJoinNode.getCorrelation();
        Optional<DecorrelatedNode> source = decorrelateFilters(lookup.resolve(aggregation.getSource()), correlation);
        if (!source.isPresent()) {
            return lateralJoinNode;
        }

        Symbol nonNull = symbolAllocator.newSymbol("non_null", BooleanType.BOOLEAN);
        Assignments scalarAggregationSourceAssignments = Assignments.builder()
                .putAll(Assignments.identity(source.get().getNode().getOutputSymbols()))
                .put(nonNull, TRUE_LITERAL)
                .build();
        ProjectNode scalarAggregationSourceWithNonNullableSymbol = new ProjectNode(
                idAllocator.getNextId(),
                source.get().getNode(),
                scalarAggregationSourceAssignments);

        return rewriteScalarAggregation(
                lateralJoinNode,
                aggregation,
                scalarAggregationSourceWithNonNullableSymbol,
                source.get().getCorrelatedPredicates(),
                nonNull);
    }

    private PlanNode rewriteScalarAggregation(
            LateralJoinNode lateralJoinNode,
            AggregationNode scalarAggregation,
            PlanNode scalarAggregationSource,
            Optional<Expression> joinExpression,
            Symbol nonNull)
    {
        AssignUniqueId inputWithUniqueColumns = new AssignUniqueId(
                idAllocator.getNextId(),
                lateralJoinNode.getInput(),
                symbolAllocator.newSymbol("unique", BigintType.BIGINT));

        JoinNode leftOuterJoin = new JoinNode(
                idAllocator.getNextId(),
                JoinNode.Type.LEFT,
                inputWithUniqueColumns,
                scalarAggregationSource,
                ImmutableList.of(),
                ImmutableList.<Symbol>builder()
                        .addAll(inputWithUniqueColumns.getOutputSymbols())
                        .addAll(scalarAggregationSource.getOutputSymbols())
                        .build(),
                joinExpression,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        Optional<AggregationNode> aggregationNode = createAggregationNode(
                scalarAggregation,
                leftOuterJoin,
                nonNull);

        if (!aggregationNode.isPresent()) {
            return lateralJoinNode;
        }

        Optional<ProjectNode> subqueryProjection = searchFrom(lateralJoinNode.getSubquery(), lookup)
                .where(ProjectNode.class::isInstance)
                .skipOnlyWhen(EnforceSingleRowNode.class::isInstance)
                .findFirst();

        List<Symbol> aggregationOutputSymbols = getTruncatedAggregationSymbols(lateralJoinNode, aggregationNode.get());

        if (subqueryProjection.isPresent()) {
            Assignments assignments = Assignments.builder()
                    .putAll(Assignments.identity(aggregationOutputSymbols))
                    .putAll(subqueryProjection.get().getAssignments())
                    .build();

            return new ProjectNode(
                    idAllocator.getNextId(),
                    aggregationNode.get(),
                    assignments);
        }
        else {
            Assignments assignments = Assignments.builder()
                    .putAll(Assignments.identity(aggregationOutputSymbols))
                    .build();

            return new ProjectNode(
                    idAllocator.getNextId(),
                    aggregationNode.get(),
                    assignments);
        }
    }

    private static List<Symbol> getTruncatedAggregationSymbols(LateralJoinNode lateralJoinNode, AggregationNode aggregationNode)
    {
        Set<Symbol> applySymbols = new HashSet<>(lateralJoinNode.getOutputSymbols());
        return aggregationNode.getOutputSymbols().stream()
                .filter(symbol -> applySymbols.contains(symbol))
                .collect(toImmutableList());
    }

    private Optional<AggregationNode> createAggregationNode(
            AggregationNode scalarAggregation,
            JoinNode leftOuterJoin,
            Symbol nonNullableAggregationSourceSymbol)
    {
        ImmutableMap.Builder<Symbol, Aggregation> aggregations = ImmutableMap.builder();
        ImmutableMap.Builder<Symbol, Signature> functions = ImmutableMap.builder();
        for (Map.Entry<Symbol, Aggregation> entry : scalarAggregation.getAggregations().entrySet()) {
            FunctionCall call = entry.getValue().getCall();
            Symbol symbol = entry.getKey();
            if (call.getName().equals(COUNT)) {
                List<TypeSignature> scalarAggregationSourceTypeSignatures = ImmutableList.of(
                        symbolAllocator.getTypes().get(nonNullableAggregationSourceSymbol).getTypeSignature());
                aggregations.put(symbol, new Aggregation(
                        new FunctionCall(
                            COUNT,
                            ImmutableList.of(nonNullableAggregationSourceSymbol.toSymbolReference())),
                        functionRegistry.resolveFunction(
                                COUNT,
                                fromTypeSignatures(scalarAggregationSourceTypeSignatures)),
                        entry.getValue().getMask()));
            }
            else {
                aggregations.put(symbol, entry.getValue());
            }
        }

        List<Symbol> groupBySymbols = leftOuterJoin.getLeft().getOutputSymbols();
        return Optional.of(new AggregationNode(
                idAllocator.getNextId(),
                leftOuterJoin,
                aggregations.build(),
                ImmutableList.of(groupBySymbols),
                scalarAggregation.getStep(),
                scalarAggregation.getHashSymbol(),
                Optional.empty()));
    }

    private Optional<DecorrelatedNode> decorrelateFilters(PlanNode node, List<Symbol> correlation)
    {
        PlanNodeSearcher filterNodeSearcher = searchFrom(node, lookup)
                .where(FilterNode.class::isInstance)
                .skipOnlyWhen(isInstanceOfAny(ProjectNode.class));
        List<FilterNode> filterNodes = filterNodeSearcher.findAll();

        if (filterNodes.isEmpty()) {
            return decorrelatedNode(ImmutableList.of(), node, correlation);
        }

        if (filterNodes.size() > 1) {
            return Optional.empty();
        }

        FilterNode filterNode = filterNodes.get(0);
        Expression predicate = filterNode.getPredicate();

        if (!isSupportedPredicate(predicate)) {
            return Optional.empty();
        }

        if (!DependencyExtractor.extractUnique(predicate).containsAll(correlation)) {
            return Optional.empty();
        }

        Map<Boolean, List<Expression>> predicates = ExpressionUtils.extractConjuncts(predicate).stream()
                .collect(Collectors.partitioningBy(isUsingPredicate(correlation)));
        List<Expression> correlatedPredicates = ImmutableList.copyOf(predicates.get(true));
        List<Expression> uncorrelatedPredicates = ImmutableList.copyOf(predicates.get(false));

        node = updateFilterNode(filterNodeSearcher, uncorrelatedPredicates);
        node = ensureJoinSymbolsAreReturned(node, correlatedPredicates);

        return decorrelatedNode(correlatedPredicates, node, correlation);
    }

    private Optional<DecorrelatedNode> decorrelatedNode(
            List<Expression> correlatedPredicates,
            PlanNode node,
            List<Symbol> correlation)
    {
        Set<Symbol> uniqueSymbols = DependencyExtractor.extractUnique(node, lookup);
        if (uniqueSymbols.stream().anyMatch(correlation::contains)) {
            // node is still correlated ; /
            return Optional.empty();
        }
        return Optional.of(new DecorrelatedNode(correlatedPredicates, node));
    }

    private static Predicate<Expression> isUsingPredicate(List<Symbol> symbols)
    {
        return expression -> symbols.stream().anyMatch(DependencyExtractor.extractUnique(expression)::contains);
    }

    private PlanNode updateFilterNode(PlanNodeSearcher filterNodeSearcher, List<Expression> newPredicates)
    {
        if (newPredicates.isEmpty()) {
            return filterNodeSearcher.removeAll();
        }
        FilterNode oldFilterNode = Iterables.getOnlyElement(filterNodeSearcher.findAll());
        FilterNode newFilterNode = new FilterNode(
                idAllocator.getNextId(),
                oldFilterNode.getSource(),
                ExpressionUtils.combineConjuncts(newPredicates));
        return filterNodeSearcher.replaceAll(newFilterNode);
    }

    private PlanNode ensureJoinSymbolsAreReturned(PlanNode scalarAggregationSource, List<Expression> joinPredicate)
    {
        Set<Symbol> joinExpressionSymbols = DependencyExtractor.extractUnique(joinPredicate);
        ExtendProjectionRewriter extendProjectionRewriter = new ExtendProjectionRewriter(
                idAllocator,
                joinExpressionSymbols);
        return rewriteWith(extendProjectionRewriter, scalarAggregationSource);
    }

    private static boolean isSupportedPredicate(Expression predicate)
    {
        AtomicBoolean isSupported = new AtomicBoolean(true);
        new DefaultTraversalVisitor<Void, AtomicBoolean>()
        {
            @Override
            protected Void visitLogicalBinaryExpression(LogicalBinaryExpression node, AtomicBoolean context)
            {
                if (node.getType() != LogicalBinaryExpression.Type.AND) {
                    context.set(false);
                }
                return null;
            }
        }.process(predicate, isSupported);
        return isSupported.get();
    }

    private static class DecorrelatedNode
    {
        private final List<Expression> correlatedPredicates;
        private final PlanNode node;

        public DecorrelatedNode(List<Expression> correlatedPredicates, PlanNode node)
        {
            requireNonNull(correlatedPredicates, "correlatedPredicates is null");
            this.correlatedPredicates = ImmutableList.copyOf(correlatedPredicates);
            this.node = requireNonNull(node, "node is null");
        }

        public Optional<Expression> getCorrelatedPredicates()
        {
            if (correlatedPredicates.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(ExpressionUtils.and(correlatedPredicates));
        }

        public PlanNode getNode()
        {
            return node;
        }
    }

    private static class ExtendProjectionRewriter
            extends SimplePlanRewriter<PlanNode>
    {
        private final PlanNodeIdAllocator idAllocator;
        private final Set<Symbol> symbols;

        ExtendProjectionRewriter(PlanNodeIdAllocator idAllocator, Set<Symbol> symbols)
        {
            this.idAllocator = requireNonNull(idAllocator, "idAllocator is null");
            this.symbols = requireNonNull(symbols, "symbols is null");
        }

        @Override
        public PlanNode visitProject(ProjectNode node, RewriteContext<PlanNode> context)
        {
            ProjectNode rewrittenNode = (ProjectNode) context.defaultRewrite(node, context.get());

            List<Symbol> symbolsToAdd = symbols.stream()
                    .filter(rewrittenNode.getSource().getOutputSymbols()::contains)
                    .filter(symbol -> !rewrittenNode.getOutputSymbols().contains(symbol))
                    .collect(toImmutableList());

            Assignments assignments = Assignments.builder()
                    .putAll(rewrittenNode.getAssignments())
                    .putAll(Assignments.identity(symbolsToAdd))
                    .build();

            return new ProjectNode(idAllocator.getNextId(), rewrittenNode.getSource(), assignments);
        }
    }
}

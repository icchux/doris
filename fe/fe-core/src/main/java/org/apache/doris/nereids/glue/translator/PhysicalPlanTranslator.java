// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.nereids.glue.translator;

import org.apache.doris.analysis.AggregateInfo;
import org.apache.doris.analysis.BaseTableRef;
import org.apache.doris.analysis.Expr;
import org.apache.doris.analysis.FunctionCallExpr;
import org.apache.doris.analysis.SlotDescriptor;
import org.apache.doris.analysis.SlotRef;
import org.apache.doris.analysis.SortInfo;
import org.apache.doris.analysis.TableName;
import org.apache.doris.analysis.TableRef;
import org.apache.doris.analysis.TupleDescriptor;
import org.apache.doris.analysis.TupleId;
import org.apache.doris.catalog.OlapTable;
import org.apache.doris.catalog.Table;
import org.apache.doris.nereids.exceptions.AnalysisException;
import org.apache.doris.nereids.operators.plans.JoinType;
import org.apache.doris.nereids.operators.plans.physical.PhysicalAggregation;
import org.apache.doris.nereids.operators.plans.physical.PhysicalFilter;
import org.apache.doris.nereids.operators.plans.physical.PhysicalHashJoin;
import org.apache.doris.nereids.operators.plans.physical.PhysicalHeapSort;
import org.apache.doris.nereids.operators.plans.physical.PhysicalOlapScan;
import org.apache.doris.nereids.operators.plans.physical.PhysicalOperator;
import org.apache.doris.nereids.operators.plans.physical.PhysicalProject;
import org.apache.doris.nereids.properties.OrderKey;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.NamedExpression;
import org.apache.doris.nereids.trees.expressions.Slot;
import org.apache.doris.nereids.trees.expressions.SlotReference;
import org.apache.doris.nereids.trees.expressions.functions.AggregateFunction;
import org.apache.doris.nereids.trees.plans.Plan;
import org.apache.doris.nereids.trees.plans.PlanOperatorVisitor;
import org.apache.doris.nereids.trees.plans.physical.PhysicalBinaryPlan;
import org.apache.doris.nereids.trees.plans.physical.PhysicalLeafPlan;
import org.apache.doris.nereids.trees.plans.physical.PhysicalPlan;
import org.apache.doris.nereids.trees.plans.physical.PhysicalUnaryPlan;
import org.apache.doris.nereids.util.Utils;
import org.apache.doris.planner.AggregationNode;
import org.apache.doris.planner.CrossJoinNode;
import org.apache.doris.planner.DataPartition;
import org.apache.doris.planner.ExchangeNode;
import org.apache.doris.planner.HashJoinNode;
import org.apache.doris.planner.OlapScanNode;
import org.apache.doris.planner.PlanFragment;
import org.apache.doris.planner.PlanNode;
import org.apache.doris.planner.SortNode;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Used to translate to physical plan generated by new optimizer to the plan fragments.
 *
 * <STRONG>
 *     ATTENTION:
 *      Must always visit plan's children first when you implement a method to translate from PhysicalPlan to PlanNode.
 * </STRONG>
 */
public class PhysicalPlanTranslator extends PlanOperatorVisitor<PlanFragment, PlanTranslatorContext> {
    private static final Logger LOG = LogManager.getLogger(PhysicalPlanTranslator.class);

    public void translatePlan(PhysicalPlan physicalPlan, PlanTranslatorContext context) {
        visit(physicalPlan, context);
    }

    @Override
    public PlanFragment visit(Plan plan, PlanTranslatorContext context) {
        PhysicalOperator operator = (PhysicalOperator) plan.getOperator();
        return operator.accept(this, plan, context);
    }

    /**
     * Translate Agg.
     */
    @Override
    public PlanFragment visitPhysicalAggregation(
            PhysicalUnaryPlan<PhysicalAggregation, Plan> agg, PlanTranslatorContext context) {

        PlanFragment inputPlanFragment = visit(agg.child(0), context);

        AggregationNode aggregationNode = null;
        List<Slot> slotList = new ArrayList<>();
        PhysicalAggregation physicalAggregation = agg.getOperator();
        AggregateInfo.AggPhase phase = physicalAggregation.getAggPhase().toExec();

        List<Expression> groupByExpressionList = physicalAggregation.getGroupByExprList();
        ArrayList<Expr> execGroupingExpressions = groupByExpressionList.stream()
                // Since output of plan doesn't contain the slots of groupBy, which is actually needed by
                // the BE execution, so we have to collect them and add to the slotList to generate corresponding
                // TupleDesc.
                .peek(x -> slotList.addAll(x.collect(SlotReference.class::isInstance)))
                .map(e -> ExpressionTranslator.translate(e, context)).collect(Collectors.toCollection(ArrayList::new));
        slotList.addAll(agg.getOutput());
        TupleDescriptor outputTupleDesc = generateTupleDesc(slotList, context, null);

        List<NamedExpression> outputExpressionList = physicalAggregation.getOutputExpressionList();
        ArrayList<FunctionCallExpr> execAggExpressions = outputExpressionList.stream()
                .map(e -> e.<List<AggregateFunction>>collect(AggregateFunction.class::isInstance))
                .flatMap(List::stream)
                .map(x -> (FunctionCallExpr) ExpressionTranslator.translate(x, context))
                .collect(Collectors.toCollection(ArrayList::new));

        List<Expression> partitionExpressionList = physicalAggregation.getPartitionExprList();
        List<Expr> execPartitionExpressions = partitionExpressionList.stream()
                .map(e -> (FunctionCallExpr) ExpressionTranslator.translate(e, context)).collect(Collectors.toList());
        // todo: support DISTINCT
        AggregateInfo aggInfo = null;
        switch (phase) {
            case FIRST:
                aggInfo = AggregateInfo.create(execGroupingExpressions, execAggExpressions, outputTupleDesc,
                        outputTupleDesc, AggregateInfo.AggPhase.FIRST);
                aggregationNode = new AggregationNode(context.nextNodeId(), inputPlanFragment.getPlanRoot(), aggInfo);
                aggregationNode.unsetNeedsFinalize();
                aggregationNode.setUseStreamingPreagg(physicalAggregation.isUsingStream());
                aggregationNode.setIntermediateTuple();
                if (!partitionExpressionList.isEmpty()) {
                    inputPlanFragment.setOutputPartition(DataPartition.hashPartitioned(execPartitionExpressions));
                }
                break;
            case FIRST_MERGE:
                aggInfo = AggregateInfo.create(execGroupingExpressions, execAggExpressions, outputTupleDesc,
                        outputTupleDesc, AggregateInfo.AggPhase.FIRST_MERGE);
                aggregationNode = new AggregationNode(context.nextNodeId(), inputPlanFragment.getPlanRoot(), aggInfo);
                break;
            default:
                throw new RuntimeException("Unsupported yet");
        }
        inputPlanFragment.setPlanRoot(aggregationNode);
        return inputPlanFragment;
    }

    @Override
    public PlanFragment visitPhysicalOlapScan(
            PhysicalLeafPlan<PhysicalOlapScan> olapScan, PlanTranslatorContext context) {
        // Create OlapScanNode
        List<Slot> slotList = olapScan.getOutput();
        PhysicalOlapScan physicalOlapScan = olapScan.getOperator();
        OlapTable olapTable = physicalOlapScan.getTable();
        List<Expr> execConjunctsList = physicalOlapScan
                .getExpressions()
                .stream()
                .map(e -> ExpressionTranslator.translate(e, context)).collect(Collectors.toList());
        TupleDescriptor tupleDescriptor = generateTupleDesc(slotList, context, olapTable);
        tupleDescriptor.setTable(olapTable);
        OlapScanNode olapScanNode = new OlapScanNode(context.nextNodeId(), tupleDescriptor, olapTable.getName());
        // TODO: Do we really need tableName here?
        TableName tableName = new TableName("", "");
        TableRef ref = new TableRef(tableName, null, null);
        BaseTableRef tableRef = new BaseTableRef(ref, olapTable, tableName);
        tupleDescriptor.setRef(tableRef);
        olapScanNode.setSelectedPartitionIds(physicalOlapScan.getSelectedPartitionId());
        try {
            olapScanNode.updateScanRangeInfoByNewMVSelector(physicalOlapScan.getSelectedIndexId(), false, "");
        } catch (Exception e) {
            throw new AnalysisException(e.getMessage());
        }
        exec(olapScanNode::init);
        olapScanNode.addConjuncts(execConjunctsList);
        context.addScanNode(olapScanNode);
        // Create PlanFragment
        PlanFragment planFragment = new PlanFragment(context.nextFragmentId(), olapScanNode, DataPartition.RANDOM);
        context.addPlanFragment(planFragment);
        return planFragment;
    }

    @Override
    public PlanFragment visitPhysicalSort(PhysicalUnaryPlan<PhysicalHeapSort, Plan> sort,
            PlanTranslatorContext context) {
        PlanFragment childFragment = visit(sort.child(0), context);
        PhysicalHeapSort physicalHeapSort = sort.getOperator();
        long limit = physicalHeapSort.getLimit();
        // TODO: need to discuss how to process field: SortNode::resolvedTupleExprs
        List<Expr> execOrderingExprList = Lists.newArrayList();
        List<Boolean> ascOrderList = Lists.newArrayList();
        List<Boolean> nullsFirstParamList = Lists.newArrayList();
        List<OrderKey> orderKeyList = physicalHeapSort.getOrderKeys();
        orderKeyList.forEach(k -> {
            execOrderingExprList.add(ExpressionTranslator.translate(k.getExpr(), context));
            ascOrderList.add(k.isAsc());
            nullsFirstParamList.add(k.isNullFirst());
        });

        List<Slot> outputList = sort.getOutput();
        TupleDescriptor tupleDesc = generateTupleDesc(outputList, context, null);
        SortInfo sortInfo = new SortInfo(execOrderingExprList, ascOrderList, nullsFirstParamList, tupleDesc);

        PlanNode childNode = childFragment.getPlanRoot();
        // TODO: notice topN
        SortNode sortNode = new SortNode(context.nextNodeId(), childNode, sortInfo, true,
                physicalHeapSort.hasLimit(), physicalHeapSort.getOffset());
        exec(sortNode::init);
        childFragment.addPlanRoot(sortNode);
        if (!childFragment.isPartitioned()) {
            return childFragment;
        }
        PlanFragment mergeFragment = createParentFragment(childFragment, DataPartition.UNPARTITIONED, context);
        ExchangeNode exchNode = (ExchangeNode) mergeFragment.getPlanRoot();
        exchNode.unsetLimit();
        if (physicalHeapSort.hasLimit()) {
            exchNode.setLimit(limit);
        }
        long offset = physicalHeapSort.getOffset();
        exchNode.setMergeInfo(sortNode.getSortInfo(), offset);

        // Child nodes should not process the offset. If there is a limit,
        // the child nodes need only return (offset + limit) rows.
        SortNode childSortNode = (SortNode) childFragment.getPlanRoot();
        Preconditions.checkState(sortNode == childSortNode);
        if (sortNode.hasLimit()) {
            childSortNode.unsetLimit();
            childSortNode.setLimit(limit + offset);
        }
        childSortNode.setOffset(0);
        context.addPlanFragment(mergeFragment);
        return mergeFragment;
    }

    // TODO: 1. support broadcast join / co-locate / bucket shuffle join later
    //       2. For ssb, there are only binary equal predicate, we shall support more in the future.
    @Override
    public PlanFragment visitPhysicalHashJoin(
            PhysicalBinaryPlan<PhysicalHashJoin, Plan, Plan> hashJoin, PlanTranslatorContext context) {
        PlanFragment leftFragment = visit(hashJoin.child(0), context);
        PlanFragment rightFragment = visit(hashJoin.child(0), context);
        PhysicalHashJoin physicalHashJoin = hashJoin.getOperator();
        Expression predicateExpr = physicalHashJoin.getCondition().get();

        //        Expression predicateExpr = physicalHashJoin.getCondition().get();
        //        List<Expression> eqExprList = Utils.getEqConjuncts(hashJoin.child(0).getOutput(),
        //                hashJoin.child(1).getOutput(), predicateExpr);
        JoinType joinType = physicalHashJoin.getJoinType();

        PlanNode leftFragmentPlanRoot = leftFragment.getPlanRoot();
        PlanNode rightFragmentPlanRoot = rightFragment.getPlanRoot();

        if (joinType.equals(JoinType.CROSS_JOIN)
                || physicalHashJoin.getJoinType().equals(JoinType.INNER_JOIN) && false /* eqExprList.isEmpty() */) {
            CrossJoinNode crossJoinNode = new CrossJoinNode(context.nextNodeId(), leftFragment.getPlanRoot(),
                    rightFragment.getPlanRoot(), null);
            crossJoinNode.setLimit(physicalHashJoin.getLimit());
            List<Expr> conjuncts = Utils.extractConjuncts(predicateExpr).stream()
                    .map(e -> ExpressionTranslator.translate(e, context))
                    .collect(Collectors.toCollection(ArrayList::new));
            crossJoinNode.addConjuncts(conjuncts);
            ExchangeNode exchangeNode = new ExchangeNode(context.nextNodeId(), rightFragment.getPlanRoot(), false);
            exchangeNode.setNumInstances(rightFragmentPlanRoot.getNumInstances());
            exchangeNode.setFragment(leftFragment);
            leftFragmentPlanRoot.setChild(1, exchangeNode);
            rightFragment.setDestination(exchangeNode);
            crossJoinNode.setChild(0, leftFragment.getPlanRoot());
            leftFragment.setPlanRoot(crossJoinNode);
            context.addPlanFragment(leftFragment);
            return leftFragment;
        }
        List<Expr> execEqConjunctList = Lists.newArrayList(ExpressionTranslator.translate(predicateExpr, context));

        HashJoinNode hashJoinNode = new HashJoinNode(context.nextNodeId(), leftFragmentPlanRoot, rightFragmentPlanRoot,
                JoinType.toJoinOperator(physicalHashJoin.getJoinType()), execEqConjunctList, Lists.newArrayList());

        ExchangeNode leftExch = new ExchangeNode(context.nextNodeId(), leftFragmentPlanRoot, false);
        leftExch.setNumInstances(leftFragmentPlanRoot.getNumInstances());
        ExchangeNode rightExch = new ExchangeNode(context.nextNodeId(), leftFragmentPlanRoot, false);
        rightExch.setNumInstances(rightFragmentPlanRoot.getNumInstances());
        hashJoinNode.setChild(0, leftFragmentPlanRoot);
        hashJoinNode.setChild(1, leftFragmentPlanRoot);
        hashJoinNode.setDistributionMode(HashJoinNode.DistributionMode.PARTITIONED);
        hashJoinNode.setLimit(physicalHashJoin.getLimit());
        leftFragment.setDestination((ExchangeNode) rightFragment.getPlanRoot());
        rightFragment.setDestination((ExchangeNode) leftFragmentPlanRoot);
        PlanFragment result = new PlanFragment(context.nextFragmentId(), hashJoinNode, leftFragment.getDataPartition());
        context.addPlanFragment(result);
        return result;
    }

    // TODO: generate expression mapping when be project could do in ExecNode
    @Override
    public PlanFragment visitPhysicalProject(
            PhysicalUnaryPlan<PhysicalProject, Plan> projectPlan, PlanTranslatorContext context) {
        PlanFragment inputFragment = visit(projectPlan.child(0), context);
        PhysicalProject physicalProject = projectPlan.getOperator();
        List<Expr> execExprList = physicalProject.getProjects()
                .stream()
                .map(e -> ExpressionTranslator.translate(e, context))
                .collect(Collectors.toList());
        PlanNode inputPlanNode = inputFragment.getPlanRoot();
        List<Expr> predicateList = inputPlanNode.getConjuncts();
        Set<Integer> requiredSlotIdList = new HashSet<>();
        for (Expr expr : predicateList) {
            extractExecSlot(expr, requiredSlotIdList);
        }
        for (Expr expr : execExprList) {
            if (expr instanceof SlotRef) {
                requiredSlotIdList.add(((SlotRef) expr).getDesc().getId().asInt());
            }
        }
        for (TupleId tupleId : inputPlanNode.getTupleIds()) {
            TupleDescriptor tupleDescriptor = context.getTupleDesc(tupleId);
            Preconditions.checkNotNull(tupleDescriptor);
            List<SlotDescriptor> slotDescList = tupleDescriptor.getSlots();
            slotDescList.removeIf(slotDescriptor -> !requiredSlotIdList.contains(slotDescriptor.getId().asInt()));
            for (int i = 0; i < slotDescList.size(); i++) {
                slotDescList.get(i).setSlotOffset(i);
            }
        }
        return inputFragment;
    }

    private void extractExecSlot(Expr root, Set<Integer>  slotRefList) {
        if (root instanceof SlotRef) {
            slotRefList.add(((SlotRef) root).getDesc().getId().asInt());
            return;
        }
        for (Expr child : root.getChildren()) {
            extractExecSlot(child, slotRefList);
        }
    }

    @Override
    public PlanFragment visitPhysicalFilter(
            PhysicalUnaryPlan<PhysicalFilter, Plan> filterPlan, PlanTranslatorContext context) {
        PlanFragment inputFragment = visit(filterPlan.child(0), context);
        PlanNode planNode = inputFragment.getPlanRoot();
        PhysicalFilter filter = filterPlan.getOperator();
        Expression expression = filter.getPredicates();
        List<Expression> expressionList = Utils.extractConjuncts(expression);
        expressionList.stream().map(e -> {
            return ExpressionTranslator.translate(e, context);
        }).forEach(planNode::addConjunct);
        return inputFragment;
    }

    private TupleDescriptor generateTupleDesc(List<Slot> slotList, PlanTranslatorContext context, Table table) {
        TupleDescriptor tupleDescriptor = context.generateTupleDesc();
        tupleDescriptor.setTable(table);
        for (Slot slot : slotList) {
            context.createSlotDesc(tupleDescriptor, (SlotReference) slot);
        }
        return tupleDescriptor;
    }

    private PlanFragment createParentFragment(PlanFragment childFragment, DataPartition parentPartition,
            PlanTranslatorContext ctx) {
        ExchangeNode exchangeNode = new ExchangeNode(ctx.nextNodeId(), childFragment.getPlanRoot(), false);
        exchangeNode.setNumInstances(childFragment.getPlanRoot().getNumInstances());
        PlanFragment parentFragment = new PlanFragment(ctx.nextFragmentId(), exchangeNode, parentPartition);
        childFragment.setDestination(exchangeNode);
        childFragment.setOutputPartition(parentPartition);
        return parentFragment;
    }

    /**
     * Helper function to eliminate unnecessary checked exception caught requirement from the main logic of translator.
     *
     * @param f function which would invoke the logic of
     *        stale code from old optimizer that could throw
     *        a checked exception
     */
    public void exec(FuncWrapper f) {
        try {
            f.exec();
        } catch (Exception e) {
            throw new RuntimeException("Unexpected Exception: ", e);
        }
    }

    private interface FuncWrapper {
        void exec() throws Exception;
    }
}

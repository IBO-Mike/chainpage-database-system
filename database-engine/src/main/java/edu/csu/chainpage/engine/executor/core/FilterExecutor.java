package edu.csu.chainpage.engine.executor.core;

import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.executor.ExecutionValue;
import edu.csu.chainpage.engine.executor.PlanExecutor;
import edu.csu.chainpage.engine.executor.expression.ExpressionEvaluator;
import edu.csu.chainpage.engine.plan.JsonPlanNode;
import edu.csu.chainpage.engine.plan.PlanDispatcher;
import edu.csu.chainpage.engine.plan.PlanNode;
import edu.csu.chainpage.engine.storage.InternalRow;
import edu.csu.chainpage.engine.storage.RowSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// 执行Filter计划，在子计划行集上逐行计算WHERE谓词
public final class FilterExecutor implements PlanExecutor {

    private final PlanDispatcher dispatcher; // 用于执行唯一子计划
    private final ExpressionEvaluator evaluator; // WHERE表达式求值器

    // 使用默认分派器和表达式求值器创建过滤执行器，便于单独测试filter方法
    public FilterExecutor() {
        this(new PlanDispatcher());
    }

    // 使用默认表达式求值器创建过滤执行器
    public FilterExecutor(PlanDispatcher dispatcher) {
        this(dispatcher, new ExpressionEvaluator());
    }

    // 使用新的默认分派器和调用方提供的表达式求值器
    public FilterExecutor(ExpressionEvaluator evaluator) {
        this(new PlanDispatcher(), evaluator);
    }

    // 创建可替换表达式求值器的过滤执行器
    public FilterExecutor(
            PlanDispatcher dispatcher,
            ExpressionEvaluator evaluator) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher cannot be null");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator cannot be null");
    }

    // 仅接受Filter计划节点
    @Override
    public boolean supports(String kind) {
        return "Filter".equals(kind);
    }

    // 取得唯一子计划结果并筛选出谓词为真的记录
    @Override
    public DbResult<ExecutionValue> execute(String requestId, PlanNode plan) {
        if (plan == null || !supports(plan.kind())) {
            return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "Filter执行器收到错误节点");
        }
        DbResult<JsonPlanNode> jsonPlan = ExecutorSupport.asJsonPlan(requestId, plan);
        if (!jsonPlan.isOk()) {
            return DbResult.fail(jsonPlan.error());
        }
        if (!jsonPlan.data().fields().containsKey("predicate")
                || jsonPlan.data().field("predicate") == null) {
            return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "Filter节点必须包含predicate字段");
        }
        DbResult<Void> childCount = dispatcher.validateChildCount(plan, 1);
        if (!childCount.isOk()) {
            return ExecutorSupport.failureFrom(childCount.error(), requestId);
        }
        DbResult<ExecutionValue> child = dispatcher.executeChild(requestId, plan, 0);
        if (!child.isOk()) {
            return DbResult.fail(child.error());
        }
        if (child.data() == null || !child.data().isRowSet()) {
            return ExecutorSupport.failure(requestId, "EXECUTOR_PREDICATE_ERROR", "Filter子计划必须返回内部行集");
        }
        DbResult<RowSet> filtered = filter(child.data().rowSet(), jsonPlan.data().field("predicate"));
        if (!filtered.isOk()) {
            return ExecutorSupport.failureFrom(filtered.error(), requestId);
        }
        return DbResult.ok(ExecutionValue.rows(filtered.data()));
    }

    // 保留谓词计算结果为true的行
    public DbResult<RowSet> filter(RowSet input, Object predicate) {
        if (input == null || predicate == null) {
            return ExecutorSupport.failure(null, "EXECUTOR_PREDICATE_ERROR", "Filter输入行集和谓词不能为null");
        }
        List<InternalRow> matched = new ArrayList<>();
        for (InternalRow row : input.rows()) {
            if (row == null || row.values() == null) {
                return ExecutorSupport.failure(null, "EXECUTOR_PREDICATE_ERROR", "行集包含无效记录");
            }
            DbResult<Boolean> result = evaluator.evaluatePredicate(predicate, row.values());
            if (!result.isOk()) {
                return DbResult.fail(result.error());
            }
            if (Boolean.TRUE.equals(result.data())) {
                matched.add(row);
            }
        }
        return DbResult.ok(new RowSet(input.schema(), matched));
    }
}

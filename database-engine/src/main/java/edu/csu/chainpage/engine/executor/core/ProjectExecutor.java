package edu.csu.chainpage.engine.executor.core;

import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.executor.CommandResult;
import edu.csu.chainpage.engine.executor.ExecutionValue;
import edu.csu.chainpage.engine.executor.PlanExecutor;
import edu.csu.chainpage.engine.plan.JsonPlanNode;
import edu.csu.chainpage.engine.plan.PlanDispatcher;
import edu.csu.chainpage.engine.plan.PlanNode;
import edu.csu.chainpage.engine.storage.ColumnSchema;
import edu.csu.chainpage.engine.storage.InternalRow;
import edu.csu.chainpage.engine.storage.RowSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

// 执行Project计划，按照SELECT列顺序把内部行集转换为用户结果
public final class ProjectExecutor implements PlanExecutor {

    private final PlanDispatcher dispatcher; // 用于执行唯一子计划

    // 使用默认分派器创建投影执行器，便于单独测试列解析方法
    public ProjectExecutor() {
        this(new PlanDispatcher());
    }

    // 创建投影执行器
    public ProjectExecutor(PlanDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher cannot be null");
    }

    // 仅接受Project计划节点
    @Override
    public boolean supports(String kind) {
        return "Project".equals(kind);
    }

    // 取得子计划行集并构造最终SELECT结果
    @Override
    public DbResult<ExecutionValue> execute(String requestId, PlanNode plan) {
        if (plan == null || !supports(plan.kind())) {
            return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "Project执行器收到错误节点");
        }
        DbResult<JsonPlanNode> jsonPlan = ExecutorSupport.asJsonPlan(requestId, plan);
        if (!jsonPlan.isOk()) {
            return DbResult.fail(jsonPlan.error());
        }
        DbResult<Void> childCount = dispatcher.validateChildCount(plan, 1);
        if (!childCount.isOk()) {
            return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "Project节点必须拥有一个子计划");
        }
        DbResult<ExecutionValue> child = dispatcher.executeChild(requestId, plan, 0);
        if (!child.isOk()) {
            return DbResult.fail(child.error());
        }
        if (child.data() == null || !child.data().isRowSet()) {
            return ExecutorSupport.failure(requestId, "EXECUTOR_PROJECT_ERROR", "Project子计划必须返回内部行集");
        }
        RowSet input = child.data().rowSet();
        DbResult<List<String>> columns = resolveColumns(plan, input);
        if (!columns.isOk()) {
            return ExecutorSupport.failureFrom(columns.error(), requestId);
        }
        DbResult<List<List<Object>>> rows = projectRows(input, columns.data());
        if (!rows.isOk()) {
            return ExecutorSupport.failureFrom(rows.error(), requestId);
        }
        return DbResult.ok(ExecutionValue.command(CommandResult.select(columns.data(), rows.data())));
    }

    // 展开SELECT *或校验并保留显式列顺序
    public DbResult<List<String>> resolveColumns(PlanNode plan, RowSet input) {
        if (plan == null || !supports(plan.kind()) || input == null) {
            return ExecutorSupport.failure(null, "EXECUTOR_PROJECT_ERROR", "Project计划和输入行集不能无效");
        }
        DbResult<JsonPlanNode> jsonPlan = ExecutorSupport.asJsonPlan(null, plan);
        if (!jsonPlan.isOk()) {
            return DbResult.fail(jsonPlan.error());
        }
        Object rawColumns = jsonPlan.data().field("columns");
        if (!(rawColumns instanceof List<?> requested) || requested.isEmpty()) {
            return ExecutorSupport.failure(null, "EXECUTOR_PROJECT_ERROR", "Project的columns必须是非空数组");
        }

        List<String> available = input.schema().stream().map(ColumnSchema::getName).toList();
        if (requested.size() == 1 && "*".equals(requested.get(0))) {
            return DbResult.ok(List.copyOf(available));
        }
        if (requested.stream().anyMatch(value -> "*".equals(value))) {
            return ExecutorSupport.failure(null, "EXECUTOR_PROJECT_ERROR", "星号不能与其他投影列混用");
        }

        List<String> resolved = new ArrayList<>();
        for (Object rawColumn : requested) {
            if (!(rawColumn instanceof String column) || column.isBlank()) {
                return ExecutorSupport.failure(null, "EXECUTOR_PROJECT_ERROR", "Project列名无效");
            }
            String normalized = column.toLowerCase(Locale.ROOT);
            if (!available.contains(normalized)) {
                return ExecutorSupport.failure(null, "EXECUTOR_PROJECT_ERROR", "Project引用了不存在的列：" + normalized);
            }
            resolved.add(normalized);
        }
        return DbResult.ok(List.copyOf(resolved));
    }

    // 按列顺序从每条记录提取二维结果行
    public DbResult<List<List<Object>>> projectRows(RowSet input, List<String> columns) {
        if (input == null || columns == null) {
            return ExecutorSupport.failure(null, "EXECUTOR_PROJECT_ERROR", "Project输入不能为null");
        }
        List<List<Object>> projected = new ArrayList<>();
        for (InternalRow internalRow : input.rows()) {
            if (internalRow == null || internalRow.values() == null) {
                return ExecutorSupport.failure(null, "EXECUTOR_PROJECT_ERROR", "输入行集包含无效记录");
            }
            List<Object> row = new ArrayList<>();
            for (String column : columns) {
                if (column == null || column.isBlank() || !internalRow.values().contains(column)) {
                    return ExecutorSupport.failure(null, "EXECUTOR_PROJECT_ERROR", "记录缺少投影列：" + column);
                }
                row.add(internalRow.values().valueOf(column));
            }
            projected.add(List.copyOf(row));
        }
        return DbResult.ok(List.copyOf(projected));
    }
}

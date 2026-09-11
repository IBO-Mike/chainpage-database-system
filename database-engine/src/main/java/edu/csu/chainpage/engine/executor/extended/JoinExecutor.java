package edu.csu.chainpage.engine.executor.extended;

import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.executor.ExecutionValue;
import edu.csu.chainpage.engine.executor.PlanExecutor;
import edu.csu.chainpage.engine.executor.core.ExecutorSupport;
import edu.csu.chainpage.engine.plan.JsonPlanNode;
import edu.csu.chainpage.engine.plan.PlanDispatcher;
import edu.csu.chainpage.engine.plan.PlanNode;
import edu.csu.chainpage.engine.storage.ColumnSchema;
import edu.csu.chainpage.engine.storage.InternalRow;
import edu.csu.chainpage.engine.storage.Row;
import edu.csu.chainpage.engine.storage.RowSet;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// 执行Join计划，按照左右连接键组合两个子计划的匹配记录
public final class JoinExecutor implements PlanExecutor {

    private final PlanDispatcher dispatcher; // 用于取得左右两个子计划结果

    // 使用默认分派器创建连接执行器，便于独立测试连接方法
    public JoinExecutor() {
        this(new PlanDispatcher());
    }

    // 创建使用指定计划分派器的连接执行器
    public JoinExecutor(PlanDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher cannot be null");
    }

    // 仅接受Join计划节点
    @Override
    public boolean supports(String kind) {
        return "Join".equals(kind);
    }

    // 执行左右子计划并按照连接键返回合并行集
    @Override
    public DbResult<ExecutionValue> execute(String requestId, PlanNode plan) {
        if (plan == null || !supports(plan.kind())) {
            return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "Join执行器收到错误节点");
        }
        DbResult<JsonPlanNode> jsonPlan = ExecutorSupport.asJsonPlan(requestId, plan);
        if (!jsonPlan.isOk()) {
            return DbResult.fail(jsonPlan.error());
        }
        DbResult<Void> childCount = dispatcher.validateChildCount(plan, 2);
        if (!childCount.isOk()) {
            return ExecutorSupport.failureFrom(childCount.error(), requestId);
        }
        DbResult<String> leftKey = ExecutorSupport.requiredString(requestId, jsonPlan.data(), "leftKey");
        if (!leftKey.isOk()) {
            return DbResult.fail(leftKey.error());
        }
        DbResult<String> rightKey = ExecutorSupport.requiredString(requestId, jsonPlan.data(), "rightKey");
        if (!rightKey.isOk()) {
            return DbResult.fail(rightKey.error());
        }

        DbResult<ExecutionValue> left = dispatcher.executeChild(requestId, plan, 0);
        if (!left.isOk()) {
            return DbResult.fail(left.error());
        }
        DbResult<ExecutionValue> right = dispatcher.executeChild(requestId, plan, 1);
        if (!right.isOk()) {
            return DbResult.fail(right.error());
        }
        if (left.data() == null || !left.data().isRowSet()
                || right.data() == null || !right.data().isRowSet()) {
            return ExecutorSupport.failure(requestId, "EXECUTOR_JOIN_ERROR", "Join的两个子计划都必须返回内部行集");
        }

        DbResult<RowSet> joined = join(
                left.data().rowSet(),
                right.data().rowSet(),
                leftKey.data(),
                rightKey.data()
        );
        if (!joined.isOk()) {
            return ExecutorSupport.failureFrom(joined.error(), requestId);
        }
        return DbResult.ok(ExecutionValue.rows(joined.data()));
    }

    // 使用嵌套循环连接两个行集，并保留所有连接键相等的组合
    public DbResult<RowSet> join(
            RowSet left,
            RowSet right,
            String leftKey,
            String rightKey) {
        if (left == null || right == null || leftKey == null || rightKey == null
                || leftKey.isBlank() || rightKey.isBlank()) {
            return ExecutorSupport.failure(null, "EXECUTOR_JOIN_ERROR", "连接输入和连接键不能为空");
        }
        ColumnSchema leftColumn = findColumn(left, leftKey);
        ColumnSchema rightColumn = findColumn(right, rightKey);
        if (leftColumn == null || rightColumn == null) {
            return ExecutorSupport.failure(
                    null,
                    "EXECUTOR_JOIN_COLUMN_NOT_FOUND",
                    "连接键在输入模式中不存在"
            );
        }
        if (!leftColumn.getDataType().equals(rightColumn.getDataType())) {
            return ExecutorSupport.failure(
                    null,
                    "EXECUTOR_JOIN_TYPE_MISMATCH",
                    "左右连接键的数据类型不一致"
            );
        }

        List<ColumnSchema> outputSchema = new ArrayList<>();
        Set<String> columnNames = new HashSet<>();
        for (ColumnSchema column : left.schema()) {
            if (column == null || !columnNames.add(column.getName())) {
                return ExecutorSupport.failure(null, "EXECUTOR_JOIN_COLUMN_CONFLICT", "左侧行集包含重复列名");
            }
            outputSchema.add(column);
        }
        for (ColumnSchema column : right.schema()) {
            if (column == null || !columnNames.add(column.getName())) {
                return ExecutorSupport.failure(
                        null,
                        "EXECUTOR_JOIN_COLUMN_CONFLICT",
                        "连接结果存在同名列：" + (column == null ? "null" : column.getName())
                );
            }
            outputSchema.add(column);
        }

        String normalizedLeftKey = leftColumn.getName();
        String normalizedRightKey = rightColumn.getName();
        DbResult<Void> leftRows = validateRows(left, normalizedLeftKey, leftColumn.getDataType());
        if (!leftRows.isOk()) {
            return DbResult.fail(leftRows.error());
        }
        DbResult<Void> rightRows = validateRows(right, normalizedRightKey, rightColumn.getDataType());
        if (!rightRows.isOk()) {
            return DbResult.fail(rightRows.error());
        }

        List<InternalRow> outputRows = new ArrayList<>();
        for (InternalRow leftRow : left.rows()) {
            Object leftValue = leftRow.values().valueOf(normalizedLeftKey);
            for (InternalRow rightRow : right.rows()) {
                Object rightValue = rightRow.values().valueOf(normalizedRightKey);
                if (!equalValues(leftValue, rightValue)) {
                    continue;
                }
                DbResult<Row> merged = mergeRows(leftRow.values(), rightRow.values());
                if (!merged.isOk()) {
                    return DbResult.fail(merged.error());
                }
                outputRows.add(new InternalRow(null, merged.data()));
            }
        }
        return DbResult.ok(new RowSet(outputSchema, outputRows));
    }

    // 合并左右两条记录，任何同名列都作为明确错误返回
    public DbResult<Row> mergeRows(Row left, Row right) {
        if (left == null || right == null) {
            return ExecutorSupport.failure(null, "EXECUTOR_JOIN_ERROR", "待合并记录不能为null");
        }
        Map<String, Object> merged = new LinkedHashMap<>(left.values());
        for (Map.Entry<String, Object> entry : right.values().entrySet()) {
            if (merged.containsKey(entry.getKey())) {
                return ExecutorSupport.failure(
                        null,
                        "EXECUTOR_JOIN_COLUMN_CONFLICT",
                        "连接结果存在同名列：" + entry.getKey()
                );
            }
            merged.put(entry.getKey(), entry.getValue());
        }
        return DbResult.ok(new Row(merged));
    }

    // 校验行集中的连接键字段和值类型
    private DbResult<Void> validateRows(RowSet input, String key, String dataType) {
        for (InternalRow row : input.rows()) {
            if (row == null || row.values() == null || !row.values().contains(key)) {
                return ExecutorSupport.failure(
                        null,
                        "EXECUTOR_JOIN_COLUMN_NOT_FOUND",
                        "连接输入记录缺少连接键：" + key
                );
            }
            Object value = row.values().valueOf(key);
            boolean matches = "INT".equals(dataType)
                    ? value instanceof Number
                    : "VARCHAR".equals(dataType) && value instanceof String;
            if (!matches) {
                return ExecutorSupport.failure(
                        null,
                        "EXECUTOR_JOIN_TYPE_MISMATCH",
                        "连接键值与模式类型不一致：" + key
                );
            }
        }
        return DbResult.ok(null);
    }

    // 按大小写不敏感的列名查找行集模式
    private ColumnSchema findColumn(RowSet input, String name) {
        for (ColumnSchema column : input.schema()) {
            if (column != null && column.getName().equalsIgnoreCase(name)) {
                return column;
            }
        }
        return null;
    }

    // 比较两个已经通过模式校验的连接键值
    private boolean equalValues(Object left, Object right) {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            return new BigDecimal(leftNumber.toString()).compareTo(
                    new BigDecimal(rightNumber.toString())
            ) == 0;
        }
        return Objects.equals(left, right);
    }
}

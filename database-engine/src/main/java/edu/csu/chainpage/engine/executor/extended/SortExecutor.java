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
import edu.csu.chainpage.engine.storage.RowSet;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// 执行Sort计划，按照一个或多个排序键返回新的内部行集
public final class SortExecutor implements PlanExecutor {

    private final PlanDispatcher dispatcher; // 用于取得唯一子计划结果

    // 使用默认分派器创建排序执行器，便于独立测试排序方法
    public SortExecutor() {
        this(new PlanDispatcher());
    }

    // 创建使用指定计划分派器的排序执行器
    public SortExecutor(PlanDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher cannot be null");
    }

    // 仅接受Sort计划节点
    @Override
    public boolean supports(String kind) {
        return "Sort".equals(kind);
    }

    // 执行唯一子计划并按计划中的排序键排列内部行集
    @Override
    public DbResult<ExecutionValue> execute(String requestId, PlanNode plan) {
        if (plan == null || !supports(plan.kind())) {
            return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "Sort执行器收到错误节点");
        }
        DbResult<JsonPlanNode> jsonPlan = ExecutorSupport.asJsonPlan(requestId, plan);
        if (!jsonPlan.isOk()) {
            return DbResult.fail(jsonPlan.error());
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
            return ExecutorSupport.failure(requestId, "EXECUTOR_SORT_ERROR", "Sort子计划必须返回内部行集");
        }

        DbResult<List<SortKey>> keys = readKeys(requestId, jsonPlan.data().field("keys"));
        if (!keys.isOk()) {
            return DbResult.fail(keys.error());
        }
        DbResult<RowSet> sorted = sort(child.data().rowSet(), keys.data());
        if (!sorted.isOk()) {
            return ExecutorSupport.failureFrom(sorted.error(), requestId);
        }
        return DbResult.ok(ExecutionValue.rows(sorted.data()));
    }

    // 返回按照给定排序键排列的新行集，不修改输入行集
    public DbResult<RowSet> sort(RowSet input, List<SortKey> keys) {
        if (input == null || keys == null || keys.isEmpty()) {
            return ExecutorSupport.failure(null, "EXECUTOR_SORT_ERROR", "排序输入和排序键不能为空");
        }
        for (SortKey key : keys) {
            if (key == null) {
                return ExecutorSupport.failure(null, "EXECUTOR_SORT_ERROR", "排序键不能为null");
            }
            ColumnSchema column = findColumn(input, key.column());
            if (column == null) {
                return ExecutorSupport.failure(
                        null,
                        "EXECUTOR_SORT_COLUMN_NOT_FOUND",
                        "排序引用了不存在的列：" + key.column()
                );
            }
            for (InternalRow row : input.rows()) {
                if (row == null || row.values() == null || !row.values().contains(key.column())) {
                    return ExecutorSupport.failure(
                            null,
                            "EXECUTOR_SORT_COLUMN_NOT_FOUND",
                            "排序输入记录缺少列：" + key.column()
                    );
                }
                Object value = row.values().valueOf(key.column());
                if (!matchesType(column.getDataType(), value)) {
                    return ExecutorSupport.failure(
                            null,
                            "EXECUTOR_SORT_TYPE_MISMATCH",
                            "排序列值与列类型不匹配：" + key.column()
                    );
                }
            }
        }

        List<InternalRow> rows = new ArrayList<>(input.rows());
        try {
            rows.sort(comparatorFor(keys));
        } catch (RuntimeException exception) {
            return ExecutorSupport.failure(null, "EXECUTOR_SORT_TYPE_MISMATCH", "排序值无法比较");
        }
        return DbResult.ok(new RowSet(input.schema(), rows));
    }

    // 构造支持ASC、DESC和多键顺序的比较器
    public Comparator<InternalRow> comparatorFor(List<SortKey> keys) {
        List<SortKey> safeKeys = List.copyOf(Objects.requireNonNull(keys, "keys cannot be null"));
        if (safeKeys.isEmpty() || safeKeys.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("keys cannot be empty or contain null");
        }
        return (left, right) -> {
            for (SortKey key : safeKeys) {
                Object leftValue = left.values().valueOf(key.column());
                Object rightValue = right.values().valueOf(key.column());
                int comparison = key.descending()
                        ? compareValues(rightValue, leftValue)
                        : compareValues(leftValue, rightValue);
                if (comparison != 0) {
                    return comparison;
                }
            }
            return 0;
        };
    }

    // 从计划字段读取并校验排序键
    private DbResult<List<SortKey>> readKeys(String requestId, Object rawKeys) {
        if (!(rawKeys instanceof List<?> list) || list.isEmpty()) {
            return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "Sort节点的keys必须是非空数组");
        }
        List<SortKey> keys = new ArrayList<>();
        for (Object rawKey : list) {
            if (!(rawKey instanceof Map<?, ?> map)
                    || !(map.get("column") instanceof String column)
                    || !(map.get("direction") instanceof String direction)) {
                return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "Sort排序键字段不完整");
            }
            try {
                keys.add(new SortKey(column, direction));
            } catch (IllegalArgumentException | NullPointerException exception) {
                return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "Sort排序键无效");
            }
        }
        return DbResult.ok(List.copyOf(keys));
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

    // 校验当前值是否符合列模式声明的数据类型
    private boolean matchesType(String dataType, Object value) {
        if (value == null) {
            return false;
        }
        return "INT".equals(dataType)
                ? value instanceof Number
                : "VARCHAR".equals(dataType) && value instanceof String;
    }

    // 比较两个已经通过类型校验的值
    private int compareValues(Object left, Object right) {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            return new BigDecimal(leftNumber.toString()).compareTo(new BigDecimal(rightNumber.toString()));
        }
        if (left instanceof String leftText && right instanceof String rightText) {
            return leftText.compareTo(rightText);
        }
        throw new IllegalArgumentException("values are not comparable");
    }
}

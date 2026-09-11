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
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// 执行GroupBy计划，按照分组键计算COUNT或SUM聚合结果
public final class GroupByExecutor implements PlanExecutor {

    private final PlanDispatcher dispatcher; // 用于取得唯一子计划结果

    // 使用默认分派器创建分组执行器，便于独立测试分组方法
    public GroupByExecutor() {
        this(new PlanDispatcher());
    }

    // 创建使用指定计划分派器的分组执行器
    public GroupByExecutor(PlanDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher cannot be null");
    }

    // 仅接受GroupBy计划节点
    @Override
    public boolean supports(String kind) {
        return "GroupBy".equals(kind);
    }

    // 执行唯一子计划并产生分组后的内部行集
    @Override
    public DbResult<ExecutionValue> execute(String requestId, PlanNode plan) {
        if (plan == null || !supports(plan.kind())) {
            return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "GroupBy执行器收到错误节点");
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
            return ExecutorSupport.failure(requestId, "EXECUTOR_GROUP_BY_ERROR", "GroupBy子计划必须返回内部行集");
        }

        DbResult<List<String>> keys = readKeys(requestId, jsonPlan.data().field("keys"));
        if (!keys.isOk()) {
            return DbResult.fail(keys.error());
        }
        DbResult<List<AggregateSpec>> aggregates = readAggregates(
                requestId,
                jsonPlan.data().field("aggregates")
        );
        if (!aggregates.isOk()) {
            return DbResult.fail(aggregates.error());
        }
        DbResult<RowSet> grouped = group(child.data().rowSet(), keys.data(), aggregates.data());
        if (!grouped.isOk()) {
            return ExecutorSupport.failureFrom(grouped.error(), requestId);
        }
        return DbResult.ok(ExecutionValue.rows(grouped.data()));
    }

    // 按分组键建立分组，并为每组计算全部聚合值
    public DbResult<RowSet> group(
            RowSet input,
            List<String> keys,
            List<AggregateSpec> aggregates) {
        if (input == null || keys == null || aggregates == null) {
            return ExecutorSupport.failure(null, "EXECUTOR_GROUP_BY_ERROR", "分组输入、键和聚合定义不能为null");
        }

        List<String> normalizedKeys = new ArrayList<>();
        Set<String> outputNames = new HashSet<>();
        List<ColumnSchema> outputSchema = new ArrayList<>();
        for (String rawKey : keys) {
            if (rawKey == null || rawKey.isBlank()) {
                return ExecutorSupport.failure(null, "EXECUTOR_GROUP_BY_ERROR", "分组列名不能为空");
            }
            String key = rawKey.toLowerCase(Locale.ROOT);
            if (!outputNames.add(key)) {
                return ExecutorSupport.failure(null, "EXECUTOR_GROUP_BY_ERROR", "分组列不能重复：" + key);
            }
            ColumnSchema column = findColumn(input, key);
            if (column == null) {
                return ExecutorSupport.failure(
                        null,
                        "EXECUTOR_GROUP_BY_COLUMN_NOT_FOUND",
                        "分组引用了不存在的列：" + key
                );
            }
            normalizedKeys.add(key);
            outputSchema.add(column);
        }

        for (AggregateSpec aggregate : aggregates) {
            if (aggregate == null) {
                return ExecutorSupport.failure(null, "EXECUTOR_GROUP_BY_ERROR", "聚合定义不能为null");
            }
            if (!outputNames.add(aggregate.alias())) {
                return ExecutorSupport.failure(
                        null,
                        "EXECUTOR_GROUP_BY_ALIAS_CONFLICT",
                        "聚合别名与其他输出列冲突：" + aggregate.alias()
                );
            }
            if (!"*".equals(aggregate.column())) {
                ColumnSchema source = findColumn(input, aggregate.column());
                if (source == null) {
                    return ExecutorSupport.failure(
                            null,
                            "EXECUTOR_GROUP_BY_COLUMN_NOT_FOUND",
                            "聚合引用了不存在的列：" + aggregate.column()
                    );
                }
                if ("SUM".equals(aggregate.function()) && !"INT".equals(source.getDataType())) {
                    return ExecutorSupport.failure(
                            null,
                            "EXECUTOR_GROUP_BY_TYPE_MISMATCH",
                            "SUM只能用于INT列：" + aggregate.column()
                    );
                }
            }
            outputSchema.add(new ColumnSchema(aggregate.alias(), "INT"));
        }

        Map<List<Object>, List<InternalRow>> groupedRows = new LinkedHashMap<>();
        for (InternalRow row : input.rows()) {
            if (row == null || row.values() == null) {
                return ExecutorSupport.failure(null, "EXECUTOR_GROUP_BY_ERROR", "分组输入包含无效记录");
            }
            List<Object> keyValues = new ArrayList<>();
            for (String key : normalizedKeys) {
                if (!row.values().contains(key) || row.values().valueOf(key) == null) {
                    return ExecutorSupport.failure(
                            null,
                            "EXECUTOR_GROUP_BY_COLUMN_NOT_FOUND",
                            "分组输入记录缺少列：" + key
                    );
                }
                keyValues.add(row.values().valueOf(key));
            }
            groupedRows.computeIfAbsent(List.copyOf(keyValues), ignored -> new ArrayList<>()).add(row);
        }
        if (input.rows().isEmpty() && normalizedKeys.isEmpty()) {
            groupedRows.put(List.of(), new ArrayList<>());
        }

        List<InternalRow> outputRows = new ArrayList<>();
        for (Map.Entry<List<Object>, List<InternalRow>> entry : groupedRows.entrySet()) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (int index = 0; index < normalizedKeys.size(); index++) {
                values.put(normalizedKeys.get(index), entry.getKey().get(index));
            }
            for (AggregateSpec aggregate : aggregates) {
                DbResult<Object> aggregateValue = aggregate(entry.getValue(), aggregate);
                if (!aggregateValue.isOk()) {
                    return DbResult.fail(aggregateValue.error());
                }
                values.put(aggregate.alias(), aggregateValue.data());
            }
            outputRows.add(new InternalRow(null, new Row(values)));
        }
        return DbResult.ok(new RowSet(outputSchema, outputRows));
    }

    // 计算一组记录的COUNT或SUM结果
    public DbResult<Object> aggregate(
            List<InternalRow> groupRows,
            AggregateSpec aggregate) {
        if (groupRows == null || aggregate == null) {
            return ExecutorSupport.failure(null, "EXECUTOR_GROUP_BY_ERROR", "聚合输入不能为null");
        }
        if ("COUNT".equals(aggregate.function())) {
            if (!"*".equals(aggregate.column())) {
                for (InternalRow row : groupRows) {
                    if (row == null || row.values() == null
                            || !row.values().contains(aggregate.column())
                            || row.values().valueOf(aggregate.column()) == null) {
                        return ExecutorSupport.failure(
                                null,
                                "EXECUTOR_GROUP_BY_COLUMN_NOT_FOUND",
                                "COUNT输入记录缺少列：" + aggregate.column()
                        );
                    }
                }
            }
            return DbResult.ok(groupRows.size());
        }

        int sum = 0;
        for (InternalRow row : groupRows) {
            if (row == null || row.values() == null || !row.values().contains(aggregate.column())) {
                return ExecutorSupport.failure(
                        null,
                        "EXECUTOR_GROUP_BY_COLUMN_NOT_FOUND",
                        "SUM输入记录缺少列：" + aggregate.column()
                );
            }
            Integer value = toInteger(row.values().valueOf(aggregate.column()));
            if (value == null) {
                return ExecutorSupport.failure(
                        null,
                        "EXECUTOR_GROUP_BY_TYPE_MISMATCH",
                        "SUM输入值必须是INT：" + aggregate.column()
                );
            }
            try {
                sum = Math.addExact(sum, value);
            } catch (ArithmeticException exception) {
                return ExecutorSupport.failure(null, "EXECUTOR_GROUP_BY_OVERFLOW", "SUM结果超出INT范围");
            }
        }
        return DbResult.ok(sum);
    }

    // 从计划字段读取分组键
    private DbResult<List<String>> readKeys(String requestId, Object rawKeys) {
        if (!(rawKeys instanceof List<?> list)) {
            return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "GroupBy节点的keys必须是数组");
        }
        List<String> keys = new ArrayList<>();
        for (Object rawKey : list) {
            if (!(rawKey instanceof String key) || key.isBlank()) {
                return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "GroupBy分组键必须是非空字符串");
            }
            keys.add(key.toLowerCase(Locale.ROOT));
        }
        return DbResult.ok(List.copyOf(keys));
    }

    // 从计划字段读取聚合定义
    private DbResult<List<AggregateSpec>> readAggregates(String requestId, Object rawAggregates) {
        if (!(rawAggregates instanceof List<?> list)) {
            return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "GroupBy节点的aggregates必须是数组");
        }
        List<AggregateSpec> aggregates = new ArrayList<>();
        for (Object rawAggregate : list) {
            if (!(rawAggregate instanceof Map<?, ?> map)
                    || !(map.get("function") instanceof String function)
                    || !(map.get("column") instanceof String column)
                    || !(map.get("alias") instanceof String alias)) {
                return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "GroupBy聚合字段不完整");
            }
            try {
                aggregates.add(new AggregateSpec(function, column, alias));
            } catch (IllegalArgumentException | NullPointerException exception) {
                return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "GroupBy聚合定义无效");
            }
        }
        return DbResult.ok(List.copyOf(aggregates));
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

    // 把可接受的数字值转换成精确的32位整数
    private Integer toInteger(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return ((Number) value).intValue();
        }
        if (value instanceof Long longValue) {
            return longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE
                    ? longValue.intValue()
                    : null;
        }
        if (value instanceof BigInteger bigInteger) {
            try {
                return bigInteger.intValueExact();
            } catch (ArithmeticException exception) {
                return null;
            }
        }
        if (value instanceof BigDecimal bigDecimal) {
            try {
                return bigDecimal.intValueExact();
            } catch (ArithmeticException exception) {
                return null;
            }
        }
        return null;
    }
}

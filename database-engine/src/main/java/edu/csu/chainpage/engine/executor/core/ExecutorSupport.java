package edu.csu.chainpage.engine.executor.core;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.ColumnSchema;
import edu.csu.chainpage.engine.contract.TableSchema;
import edu.csu.chainpage.engine.plan.JsonPlanNode;
import edu.csu.chainpage.engine.plan.PlanNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// 为多个核心执行器提供计划读取、表结构转换和统一错误处理工具
public final class ExecutorSupport {

    private ExecutorSupport() {
    }

    // 将计划转换成可以读取JSON字段的计划节点
    public static DbResult<JsonPlanNode> asJsonPlan(String requestId, PlanNode plan) {
        if (!(plan instanceof JsonPlanNode jsonPlan)) {
            return failure(requestId, "EXECUTOR_INVALID_PLAN", "执行器只接受JSON计划节点");
        }
        return DbResult.ok(jsonPlan);
    }

    // 创建执行阶段错误
    public static <T> DbResult<T> failure(
            String requestId,
            String code,
            String message) {
        return DbResult.fail(DbError.executor(requestId, null, code, message));
    }

    // 把下层错误补充当前请求编号后继续返回
    public static <T> DbResult<T> failureFrom(DbError error, String requestId) {
        if (error == null) {
            return failure(requestId, "EXECUTOR_UNKNOWN_ERROR", "下层执行组件未返回错误信息");
        }
        return DbResult.fail(new DbError(
                requestId,
                error.getStatementIndex(),
                error.getStage(),
                error.getCode(),
                error.getMessage(),
                error.getLine(),
                error.getColumn(),
                error.getPageId()
        ));
    }

    // 把没有请求编号的表达式错误补充当前请求编号
    public static <T> DbResult<T> withRequest(DbResult<T> result, String requestId) {
        if (result == null) {
            return failure(requestId, "EXECUTOR_INVALID_RESULT", "执行组件返回了null结果");
        }
        if (result.isOk()) {
            return result;
        }
        return failureFrom(result.error(), requestId);
    }

    // 将目录使用的表结构转换成存储引擎表结构
    public static edu.csu.chainpage.engine.storage.TableSchema toStorageSchema(TableSchema schema) {
        List<edu.csu.chainpage.engine.storage.ColumnSchema> columns = new ArrayList<>();
        for (ColumnSchema column : schema.getColumns()) {
            columns.add(new edu.csu.chainpage.engine.storage.ColumnSchema(
                    column.getName(),
                    column.getDataType()
            ));
        }
        return new edu.csu.chainpage.engine.storage.TableSchema(schema.getName(), columns);
    }

    // 从CreateTable计划读取表名和列定义
    public static DbResult<TableSchema> tableSchemaFromPlan(String requestId, JsonPlanNode plan) {
        Object rawTable = plan.field("table");
        if (!(rawTable instanceof String table) || table.isBlank()) {
            return failure(requestId, "EXECUTOR_INVALID_PLAN", "CreateTable节点的table字段无效");
        }
        Object rawColumns = plan.field("columns");
        if (!(rawColumns instanceof List<?> list)) {
            return failure(requestId, "EXECUTOR_INVALID_PLAN", "CreateTable节点的columns必须是数组");
        }

        List<ColumnSchema> columns = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Object rawColumn : list) {
            if (rawColumn instanceof ColumnSchema column) {
                String name = column.getName();
                String dataType = column.getDataType();
                if (name == null || name.isBlank() || dataType == null || dataType.isBlank()) {
                    return failure(requestId, "EXECUTOR_INVALID_PLAN", "列定义必须包含name和dataType");
                }
                String normalizedName = name.toLowerCase(Locale.ROOT);
                String normalizedType = dataType.toUpperCase(Locale.ROOT);
                if (!names.add(normalizedName)) {
                    return failure(requestId, "EXECUTOR_INVALID_PLAN", "列名不能重复：" + normalizedName);
                }
                if (!"INT".equals(normalizedType) && !"VARCHAR".equals(normalizedType)) {
                    return failure(requestId, "EXECUTOR_INVALID_PLAN", "不支持的列类型：" + dataType);
                }
                columns.add(new ColumnSchema(normalizedName, normalizedType));
                continue;
            }
            if (!(rawColumn instanceof Map<?, ?> map)) {
                return failure(requestId, "EXECUTOR_INVALID_PLAN", "列定义必须是JSON对象");
            }
            Object rawName = map.get("name");
            Object rawType = map.get("dataType");
            if (!(rawName instanceof String name) || name.isBlank()
                    || !(rawType instanceof String dataType) || dataType.isBlank()) {
                return failure(requestId, "EXECUTOR_INVALID_PLAN", "列定义必须包含name和dataType");
            }
            String normalizedName = name.toLowerCase(Locale.ROOT);
            String normalizedType = dataType.toUpperCase(Locale.ROOT);
            if (!names.add(normalizedName)) {
                return failure(requestId, "EXECUTOR_INVALID_PLAN", "列名不能重复：" + normalizedName);
            }
            if (!"INT".equals(normalizedType) && !"VARCHAR".equals(normalizedType)) {
                return failure(requestId, "EXECUTOR_INVALID_PLAN", "不支持的列类型：" + dataType);
            }
            columns.add(new ColumnSchema(normalizedName, normalizedType));
        }
        return DbResult.ok(new TableSchema(table.toLowerCase(Locale.ROOT), columns));
    }

    // 从计划中读取一个非空字符串字段
    public static DbResult<String> requiredString(
            String requestId,
            JsonPlanNode plan,
            String field) {
        Object value = plan.field(field);
        if (!(value instanceof String text) || text.isBlank()) {
            return failure(requestId, "EXECUTOR_INVALID_PLAN", plan.kind() + "节点的" + field + "字段无效");
        }
        return DbResult.ok(text);
    }
}

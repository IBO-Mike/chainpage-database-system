package edu.csu.chainpage.engine.tx;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.plan.JsonPlanNode;
import edu.csu.chainpage.engine.plan.PlanNode;

import java.util.LinkedHashSet;
import java.util.Set;

// 从执行计划中解析权限动作和全部相关表名
public final class PlanActionResolver {

    // 把计划节点种类转换为访问控制使用的动作名称
    public DbResult<String> resolveAction(PlanNode plan) {
        if (plan == null || plan.kind() == null) {
            return failure("TX_INVALID_PLAN", "计划不能为空");
        }
        return switch (plan.kind()) {
            case "CreateTable" -> DbResult.ok("CREATE");
            case "Insert" -> DbResult.ok("INSERT");
            case "Delete" -> DbResult.ok("DELETE");
            case "Update" -> DbResult.ok("UPDATE");
            case "SeqScan", "IndexScan", "Filter", "Project", "Sort", "GroupBy", "Join" ->
                    DbResult.ok("SELECT");
            default -> failure("TX_UNSUPPORTED_PLAN", "无法识别计划动作：" + plan.kind());
        };
    }

    // 返回计划树涉及的全部表名，多个表使用英文逗号连接
    public DbResult<String> resolveTable(PlanNode plan) {
        if (plan == null) {
            return failure("TX_INVALID_PLAN", "计划不能为空");
        }
        Set<String> tables = new LinkedHashSet<>();
        DbResult<Void> collected = collectTables(plan, tables);
        if (!collected.isOk()) {
            return DbResult.fail(collected.error());
        }
        if (tables.isEmpty()) {
            return failure("TX_TABLE_NOT_FOUND", "计划中没有可用于权限检查的表名");
        }
        return DbResult.ok(String.join(",", tables));
    }

    // 递归收集当前节点和全部子节点中的表名
    private DbResult<Void> collectTables(PlanNode plan, Set<String> tables) {
        if (plan == null || plan.children() == null) {
            return failure("TX_INVALID_PLAN", "计划树包含无效节点");
        }
        if (plan instanceof JsonPlanNode jsonPlan && jsonPlan.fields().containsKey("table")) {
            Object value = jsonPlan.field("table");
            if (!(value instanceof String table) || table.isBlank()) {
                return failure("TX_INVALID_PLAN", "计划中的table字段无效");
            }
            tables.add(table.toLowerCase(java.util.Locale.ROOT));
        }
        for (PlanNode child : plan.children()) {
            DbResult<Void> childResult = collectTables(child, tables);
            if (!childResult.isOk()) {
                return childResult;
            }
        }
        return DbResult.ok(null);
    }

    // 创建事务计划解析错误
    private <T> DbResult<T> failure(String code, String message) {
        return DbResult.fail(new DbError(
                null,
                null,
                "TRANSACTION",
                code,
                message,
                null,
                null,
                null
        ));
    }
}

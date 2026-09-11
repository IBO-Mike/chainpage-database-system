package edu.csu.chainpage.engine.explain;

import edu.csu.chainpage.engine.plan.JsonPlanNode;
import edu.csu.chainpage.engine.plan.PlanNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// 将执行计划转换为便于开发者阅读的树形文本
public final class PlanTreeFormatter {

    private static final String CHILD_INDENT = "  "; // 每一级子计划增加的缩进

    // 将完整计划格式化为树形文本
    public String format(PlanNode plan) {
        Objects.requireNonNull(plan, "plan cannot be null");
        StringBuilder output = new StringBuilder();
        appendNode(output, plan, "");
        if (!output.isEmpty()) {
            output.setLength(output.length() - 1);
        }
        return output.toString();
    }

    // 递归写入当前节点以及它的全部子节点
    public void appendNode(StringBuilder output, PlanNode plan, String indent) {
        Objects.requireNonNull(output, "output cannot be null");
        Objects.requireNonNull(plan, "plan cannot be null");
        Objects.requireNonNull(indent, "indent cannot be null");

        output.append(indent).append(describe(plan)).append('\n');
        for (PlanNode child : plan.children()) {
            appendNode(output, child, indent + CHILD_INDENT);
        }
    }

    // 返回当前计划节点的种类以及用于辨认节点的关键字段
    public String describe(PlanNode plan) {
        Objects.requireNonNull(plan, "plan cannot be null");
        if (!(plan instanceof JsonPlanNode jsonPlan)) {
            return plan.kind();
        }

        List<String> details = new ArrayList<>();
        addField(details, jsonPlan, "table");
        addField(details, jsonPlan, "index");
        addField(details, jsonPlan, "columns");
        addField(details, jsonPlan, "predicate");
        addField(details, jsonPlan, "assignments");
        addField(details, jsonPlan, "keys");
        addField(details, jsonPlan, "aggregates");
        addField(details, jsonPlan, "leftKey");
        addField(details, jsonPlan, "rightKey");
        addField(details, jsonPlan, "condition");
        return details.isEmpty()
                ? plan.kind()
                : plan.kind() + " " + String.join(" ", details);
    }

    // 在字段存在时把字段名和值加入节点摘要
    private void addField(List<String> details, JsonPlanNode plan, String fieldName) {
        if (plan.fields().containsKey(fieldName)) {
            details.add(fieldName + "=" + String.valueOf(plan.field(fieldName)));
        }
    }
}

package edu.csu.chainpage.engine.api;

import edu.csu.chainpage.engine.contract.ColumnSchema;
import edu.csu.chainpage.engine.plan.JsonPlanNode;
import edu.csu.chainpage.engine.plan.PlanNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 将优化后的执行计划转换为供命令行阅读的树形文本。 */
final class ReadableExplainPlanFormatter {

    String format(PlanNode plan) {
        Objects.requireNonNull(plan, "plan cannot be null");
        StringBuilder output = new StringBuilder();
        appendPlan(output, plan, "", true);
        return output.toString().stripTrailing();
    }

    // 节点和属性共用树枝，让多层计划和双子节点计划都能看出父子关系。
    private void appendPlan(StringBuilder output, PlanNode plan, String prefix, boolean last) {
        output.append(prefix).append(last ? "└── " : "├── ").append(plan.kind()).append('\n');
        String childPrefix = prefix + (last ? "    " : "│   ");
        List<String> details = details(plan);
        int total = details.size() + plan.children().size();
        for (int index = 0; index < details.size(); index++) {
            output.append(childPrefix)
                    .append(index == total - 1 ? "└── " : "├── ")
                    .append(details.get(index)).append('\n');
        }
        for (int index = 0; index < plan.children().size(); index++) {
            appendPlan(output, plan.children().get(index), childPrefix,
                    details.size() + index == total - 1);
        }
    }

    private List<String> details(PlanNode plan) {
        if (!(plan instanceof JsonPlanNode json)) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        switch (plan.kind()) {
            case "Project" -> {
                if (json.field("columns") instanceof List<?> columns) {
                    add(lines, "输出列", join(columns));
                } else if (json.field("items") instanceof List<?> items) {
                    add(lines, "输出列", items.stream().map(item -> text(field(item, "name")))
                            .collect(Collectors.joining(", ")));
                } else {
                    add(lines, "输出列", plan.schema().stream().map(ColumnSchema::getName)
                            .collect(Collectors.joining(", ")));
                }
            }
            case "Filter" -> addExpression(lines, "条件", json.field("predicate"));
            case "SeqScan" -> {
                add(lines, "数据表", text(json.field("table")));
                add(lines, "别名", text(json.field("alias")));
            }
            case "IndexScan" -> {
                add(lines, "数据表", text(json.field("table")));
                add(lines, "索引", text(json.field("index")));
                addExpression(lines, "条件", json.field("condition"));
            }
            case "Insert" -> {
                add(lines, "目标表", text(json.field("table")));
                add(lines, "写入列", join(json.field("columns")));
                if (json.field("values") instanceof List<?> values) {
                    add(lines, "写入值", values.stream().map(this::expression)
                            .collect(Collectors.joining(", ")));
                }
                if (json.field("rows") instanceof List<?> rows) {
                    for (Object row : rows) {
                        add(lines, "写入值", joinExpressions(row));
                    }
                }
            }
            case "CreateTable" -> {
                add(lines, "目标表", text(json.field("table")));
                if (json.field("columns") instanceof List<?> columns) {
                    add(lines, "列定义", columns.stream().map(column ->
                            text(field(column, "name")) + " " + text(field(column, "dataType")))
                            .collect(Collectors.joining(", ")));
                }
            }
            case "Update" -> {
                add(lines, "目标表", text(json.field("table")));
                if (json.field("assignments") instanceof List<?> assignments) {
                    for (Object assignment : assignments) {
                        add(lines, "设置", text(field(assignment, "column")) + " = "
                                + expression(field(assignment, "value")));
                    }
                }
                addExpression(lines, "条件", json.field("predicate"));
            }
            case "Delete" -> {
                add(lines, "目标表", text(json.field("table")));
                addExpression(lines, "条件", json.field("predicate"));
            }
            case "Join" -> {
                add(lines, "连接方式", text(json.field("joinType")));
                addExpression(lines, "连接条件", json.field("predicate"));
                if (json.field("leftKey") != null && json.field("rightKey") != null) {
                    add(lines, "连接列", text(json.field("leftKey")) + " = " + text(json.field("rightKey")));
                }
            }
            case "Sort" -> {
                if (json.field("keys") instanceof List<?> keys) {
                    add(lines, "排序", keys.stream().map(key -> {
                        Object column = field(key, "column");
                        Object expr = field(key, "expression");
                        String target = column == null ? expression(expr) : text(column);
                        return target + " " + text(field(key, "direction"));
                    }).collect(Collectors.joining(", ")));
                }
            }
            case "GroupBy" -> add(lines, "分组列", join(json.field("keys")));
            default -> { /* 未知节点仍显示节点名称和子节点。 */ }
        }
        return lines;
    }

    private void addExpression(List<String> lines, String label, Object value) {
        if (value != null) {
            add(lines, label, expression(value));
        }
    }

    private void add(List<String> lines, String label, String value) {
        if (value != null && !value.isBlank()) {
            lines.add(label + ": " + value);
        }
    }

    // 去掉词法位置、类型推断等调试字段，只保留用户写出的 SQL 条件。
    private String expression(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return text(value);
        }
        String kind = text(map.get("kind"));
        return switch (kind) {
            case "BinaryExpr" -> operand(map.get("left")) + " " + text(map.get("operator"))
                    + " " + operand(map.get("right"));
            case "UnaryExpr" -> text(map.get("operator")) + " (" + expression(map.get("operand")) + ")";
            case "IdentifierExpr" -> {
                String qualifier = text(map.get("qualifier"));
                yield (qualifier.isBlank() ? "" : qualifier + ".") + text(map.get("name"));
            }
            case "LiteralExpr" -> {
                Object literal = map.get("value");
                yield literal instanceof String string ? "'" + string.replace("'", "''") + "'" : text(literal);
            }
            case "NullLiteralExpr" -> "NULL";
            case "IsNullExpr" -> expression(map.get("operand"))
                    + (Boolean.TRUE.equals(map.get("negated")) ? " IS NOT NULL" : " IS NULL");
            case "SlotRefExpr" -> text(map.get("slot"));
            case "StarExpr" -> "*";
            case "AggregateExpr" -> text(map.get("function")) + "(" + expression(map.get("argument")) + ")";
            default -> kind.isBlank() ? "<表达式>" : "<" + kind + ">";
        };
    }

    private String operand(Object value) {
        if (value instanceof Map<?, ?> map && "BinaryExpr".equals(map.get("kind"))) {
            return "(" + expression(value) + ")";
        }
        return expression(value);
    }

    private String joinExpressions(Object value) {
        if (!(value instanceof List<?> list)) {
            return expression(value);
        }
        return list.stream().map(this::expression).collect(Collectors.joining(", "));
    }

    private String join(Object value) {
        if (!(value instanceof List<?> list)) {
            return "";
        }
        return list.stream().map(this::text).collect(Collectors.joining(", "));
    }

    private Object field(Object value, String name) {
        return value instanceof Map<?, ?> map ? map.get(name) : null;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).replace('\n', ' ').replace('\r', ' ');
    }
}

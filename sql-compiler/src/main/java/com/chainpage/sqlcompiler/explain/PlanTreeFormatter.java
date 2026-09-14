package com.chainpage.sqlcompiler.explain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import static com.chainpage.sqlcompiler.explain.ExplainData.*;

/** 纯计划格式化：不访问 Catalog、不读取数据行、不计算表达式。 */
public final class PlanTreeFormatter {
    public String format(Map<String, Object> plan) {
        com.chainpage.sqlcompiler.planner.PlanContract.validate(plan);
        StringBuilder result = new StringBuilder(); append(plan, result, "", ""); return result.toString();
    }
    private void append(Map<String, Object> plan, StringBuilder output, String prefix, String connector) {
        output.append(prefix).append(connector).append(label(plan)).append(" -> ").append(schema(nodes(plan.get("schema")))).append('\n');
        List<Map<String, Object>> children = nodes(plan.get("children"));
        String childPrefix = prefix + (connector.isEmpty() ? "" : connector.equals("└── ") ? "    " : "│   ");
        for (int i = 0; i < children.size(); i++) append(children.get(i), output, childPrefix, i == children.size() - 1 ? "└── " : "├── ");
    }
    private String label(Map<String, Object> p) {
        String kind = text(p, "kind");
        return kind + " [" + switch (kind) {
            case "SeqScan" -> "table=" + id(text(p, "table"));
            case "Filter" -> expression(map(p.get("predicate")));
            case "Project" -> ((List<?>) p.get("columns")).stream().map(value -> id((String) value)).collect(Collectors.joining(", "));
            case "Join" -> text(p, "leftKey") + " = " + text(p, "rightKey");
            case "Sort" -> nodes(p.get("keys")).stream().map(key -> id(text(key, "column")) + " " + text(key, "direction")).collect(Collectors.joining(", "));
            case "GroupBy" -> "keys=" + p.get("keys") + "; aggregates=" + nodes(p.get("aggregates")).stream()
                    .map(a -> text(a, "function") + "(" + text(a, "column") + ") AS " + text(a, "alias")).collect(Collectors.joining(", "));
            case "CreateTable" -> "table=" + id(text(p, "table")) + ", columns=" + schema(nodes(p.get("columns")));
            case "Insert" -> "table=" + id(text(p, "table")) + ", columns=" + ((List<?>) p.get("columns")).stream().map(v -> id((String) v)).collect(Collectors.joining(", "))
                    + ", values=(" + nodes(p.get("values")).stream().map(this::expression).collect(Collectors.joining(", ")) + ")";
            case "Update" -> "table=" + id(text(p, "table")) + ", set=" + nodes(p.get("assignments")).stream()
                    .map(a -> id(text(a, "column")) + " = " + expression(map(a.get("value")))).collect(Collectors.joining(", "))
                    + ", where=" + predicate(p);
            case "Delete" -> "table=" + id(text(p, "table")) + ", where=" + predicate(p);
            default -> throw new IllegalArgumentException("不支持的计划节点：" + kind);
        } + "]";
    }
    private String predicate(Map<String, Object> plan) { return plan.get("predicate") == null ? "<none>" : expression(map(plan.get("predicate"))); }
    private String schema(List<Map<String, Object>> schema) {
        return schema.stream().map(c -> id(text(c, "name")) + ":" + text(c, "dataType") + (Boolean.TRUE.equals(c.get("nullable")) ? "?" : ""))
                .collect(Collectors.joining(", ", "[", "]"));
    }
    private String expression(Map<String, Object> e) {
        return switch (text(e, "kind")) {
            case "NullLiteralExpr" -> "NULL";
            case "LiteralExpr" -> {
                String type = text(e, "literalType"); Object value = e.get("value");
                if (type.equals("VARCHAR") || type.equals("DATE")) yield (type.equals("DATE") ? "DATE " : "") + "'" + escape((String) value).replace("'", "''") + "'";
                if (type.equals("BOOL")) yield Boolean.TRUE.equals(value) ? "TRUE" : "FALSE";
                yield value instanceof BigDecimal decimal ? decimal.toPlainString() : String.valueOf(value);
            }
            case "IdentifierExpr" -> {
                if (e.get("binding") instanceof Map<?, ?>) {
                    Map<String, Object> binding = map(e.get("binding")); yield id(text(binding, "table")) + "." + id(text(binding, "column"));
                }
                yield (e.get("qualifier") == null ? "" : id((String) e.get("qualifier")) + ".") + id(text(e, "name"));
            }
            case "SlotRefExpr" -> "$" + id(text(e, "slot"));
            case "StarExpr" -> e.get("qualifier") == null ? "*" : id((String) e.get("qualifier")) + ".*";
            case "UnaryExpr" -> "(" + text(e, "operator") + " " + expression(map(e.get("operand"))) + ")";
            case "BinaryExpr" -> "(" + expression(map(e.get("left"))) + " " + text(e, "operator") + " " + expression(map(e.get("right"))) + ")";
            case "IsNullExpr" -> "(" + expression(map(e.get("operand"))) + " IS " + (Boolean.TRUE.equals(e.get("negated")) ? "NOT " : "") + "NULL)";
            case "AggregateExpr" -> text(e, "function") + "(" + expression(map(e.get("argument"))) + ")";
            default -> throw new IllegalArgumentException("不支持的表达式节点：" + e.get("kind"));
        };
    }
    private static String id(String name) { return name.matches("[A-Za-z_][A-Za-z0-9_.]*") ? name : "\"" + escape(name).replace("\"", "\"\"") + "\""; }
    private static String escape(String text) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> { if (Character.isISOControl(c)) result.append(String.format("\\u%04x", (int) c)); else result.append(c); }
            }
        }
        return result.toString();
    }
}

package com.chainpage.sqlcompiler.extension;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.chainpage.sqlcompiler.extension.SqlTree.*;

/** 执行契约的内存参考实现，用于集成验证；不读写页式存储。按语句提交；失败时保留此前结果。 */
public final class InMemoryExtensionExecutor implements ExtensionExecutor {
    private record Table(List<Map<String, Object>> columns, List<Map<String, Object>> rows) {}
    private Map<String, Table> tables = new LinkedHashMap<>();
    @Override public Set<String> supportedPlanKinds() { return Set.of("CreateTable", "Insert", "Update", "Delete", "SeqScan", "Filter", "Project", "Join", "GroupBy", "Sort"); }
    @Override public Set<String> supportedTypes() { return SqlTypes.TYPES; }

    public synchronized Map<String, Object> snapshot() {
        List<Map<String, Object>> result = new ArrayList<>();
        tables.forEach((name, table) -> result.add(node("name", name, "columns", copy(table.columns))));
        return node("tables", result);
    }
    @Override public synchronized ExtensionResponse execute(Map<String, Object> request) {
        if (request != null && request.containsKey("plan")) {
            return ExtensionResponse.run("EXECUTOR", () -> {
                Map<String, Object> plan = map(copy(request.get("plan"))); ExecutionContract.check(plan, this);
                Map<String, Table> working = new LinkedHashMap<>();
                tables.forEach((name, table) -> working.put(name, new Table(nodes(copy(table.columns)), nodes(copy(table.rows)))));
                Engine engine = new Engine(working); Map<String, Object> result;
                if (Set.of("SeqScan", "Filter", "Sort", "Join", "GroupBy").contains(plan.get("kind")))
                    result = node("schema", copy(plan.get("schema")), "rows", engine.query(plan));
                else { result = engine.execute(plan); result.remove("schema"); }
                tables = working;
                return result;
            });
        }
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            List<Map<String, Object>> plans = nodes(copy(request.get("plans")));
            for (int i = 0; i < plans.size(); i++) {
                final int index = i;
                ExtensionResponse response = ExtensionResponse.run("EXECUTOR", () -> {
                    ExecutionContract.check(plans.get(index), this);
                    Map<String, Table> working = new LinkedHashMap<>();
                    tables.forEach((name, table) -> working.put(name, new Table(nodes(copy(table.columns)), nodes(copy(table.rows)))));
                    Map<String, Object> raw = new Engine(working).execute(plans.get(index));
                    Object kind = raw.remove("kind"); raw.remove("schema");
                    tables = working;
                    return node("statementIndex", index, "kind", kind, "result", raw);
                });
                if (!response.ok()) {
                    Map<String, Object> error = new LinkedHashMap<>(response.error()); error.put("statementIndex", i);
                    error.put("results", copy(results));
                    return new ExtensionResponse(false, null, error);
                }
                results.add(response.data());
            }
            return new ExtensionResponse(true, node("results", results), null);
        } catch (IllegalArgumentException | ClassCastException | NullPointerException e) {
            return new ExtensionResponse(false, null, fail("EXECUTOR", "INVALID_REQUEST", "需要 plan 或 plans", null).error);
        }
    }
    private static final class Engine {
        final Map<String, Table> tables;
        Engine(Map<String, Table> tables) { this.tables = tables; }
        Table table(String name, Map<String, Object> owner) {
            Table table = tables.get(name);
            if (table == null) throw fail("EXECUTOR", "TABLE_NOT_FOUND", "执行时表不存在：" + name, owner);
            return table;
        }
        Map<String, Object> execute(Map<String, Object> p) {
            String kind = text(p, "kind"); int affected = 0;
            if (kind.equals("Project") || Set.of("SeqScan", "Filter", "Join", "GroupBy", "Sort").contains(kind)) {
                List<Map<String, Object>> internal = kind.equals("Project") ? query(nodes(p.get("children")).get(0)) : query(p); List<Map<String, Object>> schema = nodes(p.get("schema"));
                List<List<Object>> rows = new ArrayList<>();
                for (Map<String, Object> row : internal) {
                    List<Object> values = new ArrayList<>();
                    for (int i = 0; i < schema.size(); i++) values.add(map(row.get("values")).get(kind.equals("Project") ? strings(p.get("columns")).get(i) : text(schema.get(i), "name")));
                    rows.add(values);
                }
                return node("kind", "SELECT", "columns", schema.stream().map(c -> c.get("name")).toList(), "schema", schema,
                        "rows", rows, "affectedRows", 0, "message", rows.size() + " row(s) selected");
            }
            String name = text(p, "table");
            if (kind.equals("CreateTable")) {
                if (tables.containsKey(name)) throw fail("EXECUTOR", "DUPLICATE_TABLE", "表已存在", p);
                List<Map<String, Object>> columns = nodes(p.get("columns"));
                if (columns.isEmpty() || columns.stream().map(c -> text(c, "name")).distinct().count() != columns.size())
                    throw fail("EXECUTOR", "INVALID_PLAN", "建表列为空或重复", p);
                tables.put(name, new Table(columns, new ArrayList<>()));
            } else {
                Table table = table(name, p);
                if (kind.equals("Insert")) {
                    List<String> columns = strings(p.get("columns"));
                    if (columns.isEmpty() || columns.stream().distinct().count() != columns.size()) throw fail("EXECUTOR", "INVALID_PLAN", "插入列为空或重复", p);
                    for (String column : columns) requireColumn(table, column, p);
                    for (Object raw : List.of(p.get("values"))) {
                        List<Map<String, Object>> expressions = nodes(raw);
                        if (expressions.size() != columns.size()) throw fail("EXECUTOR", "INVALID_PLAN", "插入值数量不匹配", p);
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 0; i < columns.size(); i++) row.put(columns.get(i), eval(expressions.get(i), Map.of()));
                        validateRow(table, row, p); table.rows.add(row); affected++;
                    }
                } else if (kind.equals("Update")) {
                    List<Map<String, Object>> assignments = nodes(p.get("assignments"));
                    if (assignments.isEmpty() || assignments.stream().map(a -> text(a, "column")).distinct().count() != assignments.size())
                        throw fail("EXECUTOR", "INVALID_PLAN", "UPDATE 赋值为空或重复", p);
                    for (Map<String, Object> assignment : assignments) requireColumn(table, text(assignment, "column"), p);
                    for (int i = 0; i < table.rows.size(); i++) {
                        Map<String, Object> original = table.rows.get(i), bound = bind(name, original);
                        if (!matches(map(p.get("predicate")), bound)) continue;
                        Map<String, Object> updated = new LinkedHashMap<>(original);
                        for (Map<String, Object> assignment : assignments)
                            updated.put(text(assignment, "column"), eval(map(assignment.get("value")), bound));
                        validateRow(table, updated, p); table.rows.set(i, updated); affected++;
                    }
                } else if (kind.equals("Delete")) {
                    var iterator = table.rows.iterator();
                    while (iterator.hasNext()) if (matches(map(p.get("predicate")), bind(name, iterator.next()))) { iterator.remove(); affected++; }
                } else throw fail("EXECUTOR", "UNSUPPORTED_PLAN", "未实现的写操作", p);
            }
            return node("kind", kind.equals("CreateTable") ? "CREATE" : kind.toUpperCase(java.util.Locale.ROOT),
                    "columns", List.of(), "schema", List.of(), "rows", List.of(), "affectedRows", affected,
                    "message", kind.equals("CreateTable") ? "table created" : affected + " row(s) affected");
        }
        void requireColumn(Table table, String name, Map<String, Object> owner) {
            if (table.columns.stream().noneMatch(c -> name.equals(c.get("name")))) throw fail("EXECUTOR", "COLUMN_NOT_FOUND", "写入列不存在：" + name, owner);
        }
        void validateRow(Table table, Map<String, Object> row, Map<String, Object> owner) {
            for (Map<String, Object> column : table.columns) {
                String name = text(column, "name"); row.put(name, SqlTypes.coerce(row.get(name), text(column, "dataType"),
                        !Boolean.FALSE.equals(column.get("nullable")), "EXECUTOR", owner));
            }
        }
        Map<String, Object> internal(Map<String, Object> values) { return node("rowId", null, "values", values); }
        List<Map<String, Object>> query(Map<String, Object> p) {
            String kind = text(p, "kind"); List<Map<String, Object>> children = nodes(p.get("children"));
            if (kind.equals("SeqScan")) {
                Table table = table(text(p, "table"), p); List<Map<String, Object>> schema = nodes(p.get("schema"));
                if (schema.size() != table.columns.size()) throw fail("EXECUTOR", "SCHEMA_CHANGED", "扫描表结构已改变", p);
                for (int i = 0; i < schema.size(); i++) {
                    String name = text(schema.get(i), "name"), base = text(table.columns.get(i), "name");
                    if (!(name.equals(base) || name.endsWith("." + base)) || !schema.get(i).get("dataType").equals(table.columns.get(i).get("dataType")))
                        throw fail("EXECUTOR", "SCHEMA_CHANGED", "扫描列名或类型已改变", p);
                }
                List<Map<String, Object>> result = new ArrayList<>();
                for (Map<String, Object> row : table.rows) {
                    Map<String, Object> values = new LinkedHashMap<>();
                    for (int i = 0; i < schema.size(); i++) values.put(text(schema.get(i), "name"), row.get(text(table.columns.get(i), "name")));
                    result.add(internal(values));
                }
                return result;
            }
            if (kind.equals("Join")) {
                List<Map<String, Object>> left = query(children.get(0)), right = query(children.get(1)), result = new ArrayList<>();
                for (Map<String, Object> l : left) for (Map<String, Object> r : right) {
                    Map<String, Object> lv = map(l.get("values")), rv = map(r.get("values"));
                    Object a = value(lv, text(p, "leftKey"), p), b = value(rv, text(p, "rightKey"), p);
                    if (a != null && b != null && SqlTypes.compare(a, b) == 0) {
                        Map<String, Object> row = new LinkedHashMap<>(lv);
                        for (String key : rv.keySet()) if (row.containsKey(key)) throw fail("EXECUTOR", "INVALID_PLAN", "连接列必须使用不重复的限定名", p);
                        row.putAll(rv); result.add(internal(row));
                    }
                }
                return result;
            }
            List<Map<String, Object>> input = query(children.get(0));
            return switch (kind) {
                case "Filter" -> {
                    if (p.get("predicate") == null) throw fail("EXECUTOR", "INVALID_PLAN", "Filter 必须有 predicate", p);
                    yield input.stream().filter(row -> matches(map(p.get("predicate")), map(row.get("values")))).toList();
                }
                case "Project" -> {
                    List<Map<String, Object>> result = new ArrayList<>(); List<String> columns = strings(p.get("columns"));
                    for (Map<String, Object> row : input) {
                        Map<String, Object> output = new LinkedHashMap<>();
                        for (int i = 0; i < columns.size(); i++) output.put(text(nodes(p.get("schema")).get(i), "name"), value(map(row.get("values")), columns.get(i), p));
                        result.add(internal(output));
                    }
                    yield result;
                }
                case "Sort" -> {
                    List<Map<String, Object>> result = new ArrayList<>(input);
                    result.sort((a, b) -> {
                        for (Map<String, Object> key : nodes(p.get("keys"))) {
                            Object left = value(map(a.get("values")), text(key, "column"), p), right = value(map(b.get("values")), text(key, "column"), p);
                            int comparison = left == null || right == null ? (left == right ? 0 : left == null ? 1 : -1) : Integer.signum(SqlTypes.compare(left, right));
                            if (key.get("direction").equals("DESC")) comparison = -comparison;
                            if (comparison != 0) return comparison;
                        }
                        return 0;
                    }); yield result;
                }
                case "GroupBy" -> aggregate(p, input);
                default -> throw fail("EXECUTOR", "UNSUPPORTED_PLAN", "查询中不支持该节点：" + kind, p);
            };
        }
        Object value(Map<String, Object> row, String column, Map<String, Object> owner) {
            if (!row.containsKey(column)) throw fail("EXECUTOR", "INVALID_BINDING", "行中缺少列：" + column, owner);
            return row.get(column);
        }
        List<Map<String, Object>> aggregate(Map<String, Object> p, List<Map<String, Object>> input) {
            List<String> keys = strings(p.get("keys")); List<Map<String, Object>> aggregates = nodes(p.get("aggregates"));
            Map<List<Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
            if (keys.isEmpty()) groups.put(List.of(), new ArrayList<>());
            for (Map<String, Object> row : input) {
                Map<String, Object> values = map(row.get("values")); List<Object> key = new ArrayList<>();
                for (String column : keys) key.add(SqlTypes.groupValue(value(values, column, p)));
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(values);
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (List<Map<String, Object>> rows : groups.values()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (String key : keys) row.put(key, value(rows.get(0), key, p));
                for (Map<String, Object> a : aggregates) {
                    List<Object> values = new ArrayList<>(); String column = text(a, "column"), alias = text(a, "alias");
                    for (Map<String, Object> item : rows) { Object v = column.equals("*") ? 1 : value(item, column, p); if (v != null) values.add(v); }
                    Object resultValue;
                    if (text(a, "function").equals("COUNT")) resultValue = (long) values.size();
                    else if (values.isEmpty()) resultValue = null;
                    else { BigDecimal sum = BigDecimal.ZERO; for (Object v : values) sum = sum.add(SqlTypes.decimal(v)); resultValue = sum; }
                    String type = nodes(p.get("schema")).stream().filter(c -> alias.equals(c.get("name"))).map(c -> text(c, "dataType")).findFirst().orElseThrow();
                    row.put(alias, SqlTypes.coerce(resultValue, type, true, "EXECUTOR", p));
                }
                result.add(internal(row));
            }
            return result;
        }
        boolean matches(Map<String, Object> predicate, Map<String, Object> row) { return predicate == null || Boolean.TRUE.equals(eval(predicate, row)); }
        Map<String, Object> bind(String alias, Map<String, Object> original) {
            Map<String, Object> bound = new LinkedHashMap<>(); original.forEach((name, value) -> bound.put(alias + "." + name, value)); return bound;
        }
        Object eval(Map<String, Object> e, Map<String, Object> row) {
            return switch (text(e, "kind")) {
                case "NullLiteralExpr" -> null;
                case "LiteralExpr" -> SqlTypes.coerce(e.get("value"), text(e, "inferredType"), false, "EXECUTOR", e);
                case "IdentifierExpr", "SlotRefExpr" -> {
                    String key;
                    if (text(e, "kind").equals("SlotRefExpr")) key = text(e, "slot");
                    else {
                        Map<String, Object> binding = map(e.get("binding")); String column = text(binding, "column");
                        String table = (String) binding.get("table");
                        key = table == null || table.isEmpty() || table.equals("_group") && row.containsKey(column) ? column : table + "." + column;
                        if (!row.containsKey(key) && row.containsKey(column)) key = column;
                    }
                    if (!row.containsKey(key)) throw fail("EXECUTOR", "INVALID_BINDING", "行中缺少绑定列：" + key, e);
                    yield row.get(key);
                }
                case "IsNullExpr" -> (eval(map(e.get("operand")), row) == null) != Boolean.TRUE.equals(e.get("negated"));
                case "UnaryExpr" -> {
                    Object value = eval(map(e.get("operand")), row); String operator = text(e, "operator");
                    if (!Set.of("NOT", "+", "-").contains(operator)) throw fail("EXECUTOR", "UNSUPPORTED_EXPRESSION", "一元运算符未实现", e);
                    if (value == null) yield null;
                    if (operator.equals("NOT")) yield !(Boolean) value;
                    yield SqlTypes.coerce(operator.equals("-") ? SqlTypes.decimal(value).negate() : value, text(e, "inferredType"), true, "EXECUTOR", e);
                }
                case "BinaryExpr" -> binary(e, row);
                default -> throw fail("EXECUTOR", "UNSUPPORTED_EXPRESSION", "不能在逐行表达式中执行此节点", e);
            };
        }
        Object binary(Map<String, Object> e, Map<String, Object> row) {
            String op = text(e, "operator"); Object left = eval(map(e.get("left")), row);
            if (op.equals("AND") && Boolean.FALSE.equals(left)) return false;
            if (op.equals("OR") && Boolean.TRUE.equals(left)) return true;
            Object right = eval(map(e.get("right")), row);
            if (op.equals("AND")) {
                if (Boolean.FALSE.equals(right)) return false;
                if (left == null || right == null) return null;
                return true;
            }
            if (op.equals("OR")) {
                if (Boolean.TRUE.equals(right)) return true;
                if (left == null || right == null) return null;
                return false;
            }
            if (!Set.of("=", "!=", "<", "<=", ">", ">=", "+", "-", "*", "/").contains(op))
                throw fail("EXECUTOR", "UNSUPPORTED_EXPRESSION", "二元运算符未实现", e);
            if (left == null || right == null) return null;
            if (Set.of("=", "!=", "<", "<=", ">", ">=").contains(op)) {
                int comparison = SqlTypes.compare(left, right);
                return switch (op) { case "=" -> comparison == 0; case "!=" -> comparison != 0; case "<" -> comparison < 0;
                    case "<=" -> comparison <= 0; case ">" -> comparison > 0; default -> comparison >= 0; };
            }
            BigDecimal a = SqlTypes.decimal(left), b = SqlTypes.decimal(right);
            if (op.equals("/") && b.signum() == 0) throw fail("EXECUTOR", "DIVISION_BY_ZERO", "除数不得为零", e);
            Object result = switch (op) { case "+" -> a.add(b); case "-" -> a.subtract(b); case "*" -> a.multiply(b); default -> a.divide(b, MathContext.DECIMAL128); };
            return SqlTypes.coerce(result, text(e, "inferredType"), true, "EXECUTOR", e);
        }
    }
}

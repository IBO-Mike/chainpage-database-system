package com.chainpage.sqlcompiler.ast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** AST 节点校验、序列化和反序列化接口。 */
public final class AstService {
    private static final Set<String> DATA_TYPES = Set.of("INT", "VARCHAR");
    private static final Set<String> LITERAL_TYPES = Set.of("INT", "VARCHAR", "BOOL");
    private static final Set<String> BINARY_OPERATORS = Set.of(
            "=", "!=", ">", ">=", "<", "<=", "+", "-", "*", "/", "AND", "OR");

    public AstResponse makeNode(MakeNodeRequest request) {
        if (request == null || request.node() == null)
            return failure("node 必须是对象", null);
        try {
            Map<String, Object> node = validateNode(request.node());
            return AstResponse.made(node, JsonCodec.stringify(node));
        } catch (InvalidNode error) {
            return AstResponse.failure(error.error);
        }
    }

    public AstResponse parseNode(ParseNodeRequest request) {
        if (request == null || request.json() == null)
            return failure("json 必须是字符串", null);
        try {
            Object decoded = JsonCodec.parse(request.json());
            if (!(decoded instanceof Map<?, ?> map)) return failure("JSON 根节点必须是对象", null);
            return AstResponse.parsed(validateNode(castMap(map)));
        } catch (JsonCodec.JsonException error) {
            return failure("JSON 格式错误：" + error.getMessage(), null);
        } catch (InvalidNode error) {
            return AstResponse.failure(error.error);
        }
    }

    private Map<String, Object> validateNode(Map<String, Object> input) {
        String kind = string(input, "kind");
        Map<String, Object> loc = validateLoc(input.get("loc"), input);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", kind);
        result.put("loc", loc);
        switch (kind) {
            case "CreateTableStmt" -> validateCreate(input, result);
            case "InsertStmt" -> validateInsert(input, result);
            case "SelectStmt" -> validateSelect(input, result);
            case "DeleteStmt" -> validateDelete(input, result);
            case "IdentifierExpr" -> result.put("name", nonEmptyString(input, "name"));
            case "LiteralExpr" -> validateLiteral(input, result);
            case "BinaryExpr" -> validateBinary(input, result);
            case "UnaryExpr" -> validateUnary(input, result);
            default -> throw invalid("未知 AST kind：" + kind, input);
        }
        return result;
    }

    private void validateCreate(Map<String, Object> input, Map<String, Object> result) {
        result.put("table", nonEmptyString(input, "table"));
        List<?> columns = list(input, "columns");
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Object value : columns) {
            if (!(value instanceof Map<?, ?> map)) throw invalid("columns 的元素必须是对象", input);
            Map<String, Object> column = castMap(map);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", nonEmptyString(column, "name"));
            String type = string(column, "dataType");
            if (!DATA_TYPES.contains(type)) throw invalid("dataType 只能是 INT 或 VARCHAR", column);
            item.put("dataType", type);
            item.put("loc", validateLoc(column.get("loc"), column));
            normalized.add(item);
        }
        result.put("columns", normalized);
    }

    private void validateInsert(Map<String, Object> input, Map<String, Object> result) {
        result.put("table", nonEmptyString(input, "table"));
        result.put("columns", stringList(input, "columns"));
        result.put("values", expressionList(input, "values"));
    }

    private void validateSelect(Map<String, Object> input, Map<String, Object> result) {
        List<String> columns = stringList(input, "columns");
        if (columns.isEmpty()) throw invalid("SelectStmt.columns 不得为空", input);
        if (columns.contains("*") && !(columns.size() == 1 && columns.get(0).equals("*")))
            throw invalid("* 只能单独出现在 SelectStmt.columns 中", input);
        result.put("columns", columns);
        result.put("table", nonEmptyString(input, "table"));
        result.put("where", nullableExpression(input, "where"));
    }

    private void validateDelete(Map<String, Object> input, Map<String, Object> result) {
        result.put("table", nonEmptyString(input, "table"));
        result.put("where", nullableExpression(input, "where"));
    }

    private void validateLiteral(Map<String, Object> input, Map<String, Object> result) {
        String type = string(input, "literalType");
        if (!LITERAL_TYPES.contains(type)) throw invalid("literalType 非法", input);
        Object value = input.get("value");
        if (type.equals("INT")) {
            if (!(value instanceof Number number) || number.doubleValue() != number.intValue())
                throw invalid("INT 字面量的 value 必须是整数", input);
            value = number.intValue();
        } else if (type.equals("VARCHAR") && !(value instanceof String)) {
            throw invalid("VARCHAR 字面量的 value 必须是字符串", input);
        } else if (type.equals("BOOL") && !(value instanceof Boolean)) {
            throw invalid("BOOL 字面量的 value 必须是布尔值", input);
        }
        result.put("literalType", type);
        result.put("value", value);
    }

    private void validateBinary(Map<String, Object> input, Map<String, Object> result) {
        String operator = string(input, "operator");
        if (!BINARY_OPERATORS.contains(operator)) throw invalid("BinaryExpr.operator 非法", input);
        result.put("operator", operator);
        result.put("left", expression(input, "left"));
        result.put("right", expression(input, "right"));
    }

    private void validateUnary(Map<String, Object> input, Map<String, Object> result) {
        if (!string(input, "operator").equals("NOT")) throw invalid("UnaryExpr.operator 只能是 NOT", input);
        result.put("operator", "NOT");
        result.put("operand", expression(input, "operand"));
    }

    private Map<String, Object> nullableExpression(Map<String, Object> input, String field) {
        Object value = require(input, field);
        if (value == null) return null;
        return expressionValue(value, input, field);
    }

    private Map<String, Object> expression(Map<String, Object> input, String field) {
        return expressionValue(require(input, field), input, field);
    }

    private Map<String, Object> expressionValue(Object value, Map<String, Object> owner, String field) {
        if (!(value instanceof Map<?, ?> map)) throw invalid(field + " 必须是表达式对象", owner);
        Map<String, Object> expression = validateNode(castMap(map));
        String kind = (String) expression.get("kind");
        if (!kind.endsWith("Expr")) throw invalid(field + " 必须是表达式节点", owner);
        return expression;
    }

    private List<Map<String, Object>> expressionList(Map<String, Object> input, String field) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : list(input, field)) result.add(expressionValue(value, input, field));
        return result;
    }

    private List<String> stringList(Map<String, Object> input, String field) {
        List<String> result = new ArrayList<>();
        for (Object value : list(input, field)) {
            if (!(value instanceof String string) || string.isEmpty())
                throw invalid(field + " 只能包含非空字符串", input);
            result.add(string);
        }
        return result;
    }

    private Map<String, Object> validateLoc(Object value, Map<String, Object> owner) {
        if (!(value instanceof Map<?, ?> map))
            throw invalid("loc 必须是对象", owner);
        Map<String, Object> loc = castMap(map);
        int line = positiveInt(loc.get("line"), "loc.line", owner);
        int column = positiveInt(loc.get("column"), "loc.column", owner);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("line", line);
        result.put("column", column);
        return result;
    }

    private int positiveInt(Object value, String field, Map<String, Object> owner) {
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue() || number.intValue() < 1)
            throw invalid(field + " 必须是正整数", owner);
        return number.intValue();
    }

    private List<?> list(Map<String, Object> input, String field) {
        Object value = require(input, field);
        if (!(value instanceof List<?> list)) throw invalid(field + " 必须是数组", input);
        return list;
    }

    private String nonEmptyString(Map<String, Object> input, String field) {
        String value = string(input, field);
        if (value.isEmpty()) throw invalid(field + " 不得为空", input);
        return value;
    }

    private String string(Map<String, Object> input, String field) {
        Object value = require(input, field);
        if (!(value instanceof String string)) throw invalid(field + " 必须是字符串", input);
        return string;
    }

    private Object require(Map<String, Object> input, String field) {
        if (!input.containsKey(field)) throw invalid("缺少字段：" + field, input);
        return input.get(field);
    }

    private InvalidNode invalid(String message, Map<String, Object> node) {
        Integer line = null, column = null;
        if (node != null && node.get("loc") instanceof Map<?, ?> loc) {
            if (loc.get("line") instanceof Number n) line = n.intValue();
            if (loc.get("column") instanceof Number n) column = n.intValue();
        }
        return new InvalidNode(new AstError("AST", "AST_INVALID_NODE", message, line, column));
    }

    private AstResponse failure(String message, Map<String, Object> node) {
        return AstResponse.failure(invalid(message, node).error);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static final class InvalidNode extends RuntimeException {
        private final AstError error;
        private InvalidNode(AstError error) { super(error.message()); this.error = error; }
    }
}

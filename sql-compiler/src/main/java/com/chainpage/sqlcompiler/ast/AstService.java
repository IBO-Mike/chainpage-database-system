package com.chainpage.sqlcompiler.ast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AST 节点校验、序列化和反序列化接口—— SQL 编译器的第三个板块。
 *
 * <p>职责：对以 Map/JSON 形式表示的 AST 节点做<b>结构校验与归一化</b>。Parser 产出的 AST、
 * 前端构造的 AST、或其他模块传入的 AST 都必须通过本服务校验，保证下游（语义分析、计划生成）
 * 拿到的节点字段完整、类型正确、取值合法。</p>
 *
 * <p>两个入口：</p>
 * <ul>
 *   <li>{@link #makeNode}：校验节点并返回归一化后的节点及其 JSON 序列化文本；</li>
 *   <li>{@link #parseNode}：先把 JSON 文本反序列化为节点，再做同样的校验。</li>
 * </ul>
 *
 * <p>校验按 kind 分派：语句节点（CreateTableStmt/InsertStmt/SelectStmt/DeleteStmt）与
 * 表达式节点（IdentifierExpr/LiteralExpr/BinaryExpr/UnaryExpr），递归校验所有子节点；
 * 校验失败统一抛出 InvalidNode，由入口转为 AST_INVALID_NODE 错误响应。</p>
 */
public final class AstService {
    /** 列定义允许的数据类型。 */
    private static final Set<String> DATA_TYPES = Set.of("INT", "VARCHAR");
    /** 字面量允许的类型（BOOL 仅供外部构造节点使用，Parser 不产出）。 */
    private static final Set<String> LITERAL_TYPES = Set.of("INT", "VARCHAR", "BOOL");
    /** BinaryExpr 允许的全部二元运算符。 */
    private static final Set<String> BINARY_OPERATORS = Set.of(
            "=", "!=", ">", ">=", "<", "<=", "+", "-", "*", "/", "AND", "OR");

    /**
     * 校验并归一化一个 AST 节点，同时给出其 JSON 文本。
     *
     * @param request 含 node（Map 形式的 AST）的请求
     * @return 成功携带归一化节点 + JSON 字符串；失败携带 AST_INVALID_NODE 错误
     */
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

    /**
     * 反序列化 JSON 文本为 AST 节点，再执行与 makeNode 相同的结构校验。
     *
     * @param request 含 json（AST 的 JSON 文本）的请求
     * @return 成功携带归一化节点；JSON 格式错误或结构非法时携带对应错误
     */
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

    /**
     * 节点校验主入口：先校验公共字段 kind 与 loc，再按 kind 分派到具体节点类型的校验器，
     * 结果写入新的有序 Map（归一化输出，不回传未知字段）。
     */
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

    /** CreateTableStmt：表名非空，columns 每项必须是 {name, dataType, loc} 且 dataType 合法。 */
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

    /** InsertStmt：表名非空，columns 为字符串列表，values 为表达式列表。 */
    private void validateInsert(Map<String, Object> input, Map<String, Object> result) {
        result.put("table", nonEmptyString(input, "table"));
        result.put("columns", stringList(input, "columns"));
        result.put("values", expressionList(input, "values"));
    }

    /**
     * SelectStmt：columns 非空且 "*" 只能单独出现；table 非空；where 可为 null。
     */
    private void validateSelect(Map<String, Object> input, Map<String, Object> result) {
        List<String> columns = stringList(input, "columns");
        if (columns.isEmpty()) throw invalid("SelectStmt.columns 不得为空", input);
        if (columns.contains("*") && !(columns.size() == 1 && columns.get(0).equals("*")))
            throw invalid("* 只能单独出现在 SelectStmt.columns 中", input);
        result.put("columns", columns);
        result.put("table", nonEmptyString(input, "table"));
        result.put("where", nullableExpression(input, "where"));
    }

    /** DeleteStmt：表名非空，where 可为 null。 */
    private void validateDelete(Map<String, Object> input, Map<String, Object> result) {
        result.put("table", nonEmptyString(input, "table"));
        result.put("where", nullableExpression(input, "where"));
    }

    /**
     * LiteralExpr：literalType ∈ {INT, VARCHAR, BOOL}，且 value 类型必须与之匹配；
     * INT 的 value 会归一化为 Java int。
     */
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

    /** BinaryExpr：operator 必须在白名单内，左右操作数都必须是表达式节点。 */
    private void validateBinary(Map<String, Object> input, Map<String, Object> result) {
        String operator = string(input, "operator");
        if (!BINARY_OPERATORS.contains(operator)) throw invalid("BinaryExpr.operator 非法", input);
        result.put("operator", operator);
        result.put("left", expression(input, "left"));
        result.put("right", expression(input, "right"));
    }

    /** UnaryExpr：operator 只能是 NOT，操作数必须是表达式节点。 */
    private void validateUnary(Map<String, Object> input, Map<String, Object> result) {
        if (!string(input, "operator").equals("NOT")) throw invalid("UnaryExpr.operator 只能是 NOT", input);
        result.put("operator", "NOT");
        result.put("operand", expression(input, "operand"));
    }

    /** 可空表达式字段：字段必须存在，值可以为 null，否则递归校验为表达式节点。 */
    private Map<String, Object> nullableExpression(Map<String, Object> input, String field) {
        Object value = require(input, field);
        if (value == null) return null;
        return expressionValue(value, input, field);
    }

    /** 非空表达式字段：字段必须存在且值为表达式节点。 */
    private Map<String, Object> expression(Map<String, Object> input, String field) {
        return expressionValue(require(input, field), input, field);
    }

    /** 表达式校验核心：值必须是 Map，且校验后 kind 必须以 "Expr" 结尾（即表达式类节点）。 */
    private Map<String, Object> expressionValue(Object value, Map<String, Object> owner, String field) {
        if (!(value instanceof Map<?, ?> map)) throw invalid(field + " 必须是表达式对象", owner);
        Map<String, Object> expression = validateNode(castMap(map));
        String kind = (String) expression.get("kind");
        if (!kind.endsWith("Expr")) throw invalid(field + " 必须是表达式节点", owner);
        return expression;
    }

    /** 校验“表达式数组”字段：数组每一项都必须是表达式节点。 */
    private List<Map<String, Object>> expressionList(Map<String, Object> input, String field) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : list(input, field)) result.add(expressionValue(value, input, field));
        return result;
    }

    /** 校验“非空字符串数组”字段。 */
    private List<String> stringList(Map<String, Object> input, String field) {
        List<String> result = new ArrayList<>();
        for (Object value : list(input, field)) {
            if (!(value instanceof String string) || string.isEmpty())
                throw invalid(field + " 只能包含非空字符串", input);
            result.add(string);
        }
        return result;
    }

    /** 位置信息校验：必须是 {line, column} 且均为正整数。 */
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

    /** 数值必须可无损转为正整数。 */
    private int positiveInt(Object value, String field, Map<String, Object> owner) {
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue() || number.intValue() < 1)
            throw invalid(field + " 必须是正整数", owner);
        return number.intValue();
    }

    /** 字段必须存在且为数组。 */
    private List<?> list(Map<String, Object> input, String field) {
        Object value = require(input, field);
        if (!(value instanceof List<?> list)) throw invalid(field + " 必须是数组", input);
        return list;
    }

    /** 字段必须存在且为非空字符串。 */
    private String nonEmptyString(Map<String, Object> input, String field) {
        String value = string(input, field);
        if (value.isEmpty()) throw invalid(field + " 不得为空", input);
        return value;
    }

    /** 字段必须存在且为字符串。 */
    private String string(Map<String, Object> input, String field) {
        Object value = require(input, field);
        if (!(value instanceof String string)) throw invalid(field + " 必须是字符串", input);
        return string;
    }

    /** 字段必须存在的通用检查，返回字段值。 */
    private Object require(Map<String, Object> input, String field) {
        if (!input.containsKey(field)) throw invalid("缺少字段：" + field, input);
        return input.get(field);
    }

    /**
     * 构造 AST_INVALID_NODE 错误：若出错节点带有合法 loc，则顺带提取行列号用于错误定位。
     */
    private InvalidNode invalid(String message, Map<String, Object> node) {
        Integer line = null, column = null;
        if (node != null && node.get("loc") instanceof Map<?, ?> loc) {
            if (loc.get("line") instanceof Number n) line = n.intValue();
            if (loc.get("column") instanceof Number n) column = n.intValue();
        }
        return new InvalidNode(new AstError("AST", "AST_INVALID_NODE", message, line, column));
    }

    /** 以纯消息形式构造失败响应（无节点上下文，位置为 null）。 */
    private AstResponse failure(String message, Map<String, Object> node) {
        return AstResponse.failure(invalid(message, node).error);
    }

    /** 抑制泛型的安全强转：调用点已通过 instanceof 确认键值类型。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    /** 校验失败载体：以运行时异常在递归校验中传播，由入口统一捕获转换。 */
    private static final class InvalidNode extends RuntimeException {
        private final AstError error;
        private InvalidNode(AstError error) { super(error.message()); this.error = error; }
    }
}

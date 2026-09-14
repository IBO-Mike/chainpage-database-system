package com.chainpage.sqlcompiler.semantic;

import com.chainpage.sqlcompiler.ast.AstResponse;
import com.chainpage.sqlcompiler.ast.AstService;
import com.chainpage.sqlcompiler.ast.MakeNodeRequest;
import com.chainpage.sqlcompiler.catalog.ColumnSchema;
import com.chainpage.sqlcompiler.catalog.TableSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SemanticAnalyzer {
    private static final Set<String> TYPES = Set.of("INT", "VARCHAR");
    private final AstService astService = new AstService();

    public AnalyzeResponse analyze(AnalyzeRequest request) {
        try {
            if (request == null || request.statements() == null || request.catalogSnapshot() == null)
                throw fail("SEMANTIC_INVALID_REQUEST", "请求必须包含 statements 和 catalogSnapshot", null);
            Map<String, TableSchema> catalog = readSnapshot(request.catalogSnapshot());
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> input : request.statements()) {
                AstResponse checked = astService.makeNode(new MakeNodeRequest(input));
                if (!checked.ok())
                    throw fail("SEMANTIC_INVALID_AST", checked.error().message(), input);
                Map<String, Object> statement = checked.node();
                result.add(analyzeStatement(statement, catalog));
            }
            return AnalyzeResponse.success(result);
        } catch (SemanticFailure failure) {
            return AnalyzeResponse.failure(failure.error);
        }
    }

    private Map<String, Object> analyzeStatement(Map<String, Object> statement,
                                                 Map<String, TableSchema> catalog) {
        return switch (string(statement, "kind")) {
            case "CreateTableStmt" -> analyzeCreate(statement, catalog);
            case "InsertStmt" -> analyzeInsert(statement, catalog);
            case "SelectStmt" -> analyzeSelect(statement, catalog);
            case "DeleteStmt" -> analyzeDelete(statement, catalog);
            default -> throw fail("SEMANTIC_UNKNOWN_STATEMENT", "未知语句节点", statement);
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> analyzeCreate(Map<String, Object> statement,
                                             Map<String, TableSchema> catalog) {
        String tableName = normalize(string(statement, "table"));
        if (catalog.containsKey(tableName))
            throw fail("SEMANTIC_TABLE_EXISTS", "表已存在：" + tableName, statement);
        List<Map<String, Object>> columns = (List<Map<String, Object>>) statement.get("columns");
        Set<String> names = new LinkedHashSet<>();
        List<ColumnSchema> schemaColumns = new ArrayList<>();
        for (Map<String, Object> column : columns) {
            String name = normalize(string(column, "name"));
            if (!names.add(name)) throw fail("SEMANTIC_DUPLICATE_COLUMN", "列名重复：" + name, column);
            schemaColumns.add(new ColumnSchema(name, string(column, "dataType")));
        }
        catalog.put(tableName, new TableSchema(tableName, schemaColumns));
        return statement;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> analyzeInsert(Map<String, Object> statement,
                                             Map<String, TableSchema> catalog) {
        TableSchema table = requireTable(string(statement, "table"), statement, catalog);
        List<String> columns = (List<String>) statement.get("columns");
        List<Map<String, Object>> values = (List<Map<String, Object>>) statement.get("values");
        if (columns.size() != values.size())
            throw fail("SEMANTIC_INSERT_ARITY_MISMATCH", "INSERT 的列数和值数不一致", statement);
        Set<String> used = new LinkedHashSet<>();
        List<Map<String, Object>> annotatedValues = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            String columnName = normalize(columns.get(i));
            if (!used.add(columnName))
                throw fail("SEMANTIC_DUPLICATE_INSERT_COLUMN", "INSERT 列重复：" + columnName, statement);
            ColumnSchema column = requireColumn(table, columnName, statement);
            TypedExpression typed = analyzeExpression(values.get(i), table);
            if (!typed.type.equals(column.dataType()))
                throw fail("SEMANTIC_TYPE_MISMATCH",
                        "列 " + column.name() + " 需要 " + column.dataType() + "，实际为 " + typed.type,
                        values.get(i));
            annotatedValues.add(typed.node);
        }
        Map<String, Object> result = copy(statement);
        result.put("values", annotatedValues);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> analyzeSelect(Map<String, Object> statement,
                                             Map<String, TableSchema> catalog) {
        TableSchema table = requireTable(string(statement, "table"), statement, catalog);
        List<String> columns = (List<String>) statement.get("columns");
        if (!(columns.size() == 1 && columns.get(0).equals("*"))) {
            for (String column : columns) requireColumn(table, normalize(column), statement);
        }
        Map<String, Object> result = copy(statement);
        result.put("resolvedTable", table.toMap());
        result.put("where", analyzeWhere((Map<String, Object>) statement.get("where"), table));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> analyzeDelete(Map<String, Object> statement,
                                             Map<String, TableSchema> catalog) {
        TableSchema table = requireTable(string(statement, "table"), statement, catalog);
        Map<String, Object> result = copy(statement);
        result.put("where", analyzeWhere((Map<String, Object>) statement.get("where"), table));
        return result;
    }

    private Map<String, Object> analyzeWhere(Map<String, Object> where, TableSchema table) {
        if (where == null) return null;
        TypedExpression typed = analyzeExpression(where, table);
        if (!typed.type.equals("BOOL"))
            throw fail("SEMANTIC_WHERE_NOT_BOOL", "WHERE 表达式必须为 BOOL", where);
        return typed.node;
    }

    private TypedExpression analyzeExpression(Map<String, Object> expression, TableSchema table) {
        String kind = string(expression, "kind");
        return switch (kind) {
            case "IdentifierExpr" -> analyzeIdentifier(expression, table);
            case "LiteralExpr" -> annotate(expression, string(expression, "literalType"));
            case "UnaryExpr" -> analyzeUnary(expression, table);
            case "BinaryExpr" -> analyzeBinary(expression, table);
            default -> throw fail("SEMANTIC_INVALID_EXPRESSION", "未知表达式节点：" + kind, expression);
        };
    }

    private TypedExpression analyzeIdentifier(Map<String, Object> expression, TableSchema table) {
        ColumnSchema column = requireColumn(table, normalize(string(expression, "name")), expression);
        Map<String, Object> result = copy(expression);
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("table", table.name());
        binding.put("column", column.name());
        binding.put("dataType", column.dataType());
        result.put("binding", binding);
        result.put("inferredType", column.dataType());
        return new TypedExpression(result, column.dataType());
    }

    @SuppressWarnings("unchecked")
    private TypedExpression analyzeUnary(Map<String, Object> expression, TableSchema table) {
        TypedExpression operand = analyzeExpression((Map<String, Object>) expression.get("operand"), table);
        if (!operand.type.equals("BOOL"))
            throw fail("SEMANTIC_OPERATOR_TYPE_MISMATCH", "NOT 的操作数必须为 BOOL", expression);
        Map<String, Object> result = copy(expression);
        result.put("operand", operand.node);
        result.put("inferredType", "BOOL");
        return new TypedExpression(result, "BOOL");
    }

    @SuppressWarnings("unchecked")
    private TypedExpression analyzeBinary(Map<String, Object> expression, TableSchema table) {
        TypedExpression left = analyzeExpression((Map<String, Object>) expression.get("left"), table);
        TypedExpression right = analyzeExpression((Map<String, Object>) expression.get("right"), table);
        String operator = string(expression, "operator");
        String resultType;
        if (Set.of("AND", "OR").contains(operator)) {
            if (!left.type.equals("BOOL") || !right.type.equals("BOOL"))
                throw fail("SEMANTIC_OPERATOR_TYPE_MISMATCH", operator + " 的两侧必须为 BOOL", expression);
            resultType = "BOOL";
        } else if (Set.of("=", "!=", ">", ">=", "<", "<=").contains(operator)) {
            if (!left.type.equals(right.type) || !TYPES.contains(left.type))
                throw fail("SEMANTIC_COMPARISON_TYPE_MISMATCH", "比较运算两侧必须是相同的 INT 或 VARCHAR", expression);
            resultType = "BOOL";
        } else {
            if (!left.type.equals("INT") || !right.type.equals("INT"))
                throw fail("SEMANTIC_OPERATOR_TYPE_MISMATCH", operator + " 的两侧必须为 INT", expression);
            resultType = "INT";
        }
        Map<String, Object> result = copy(expression);
        result.put("left", left.node);
        result.put("right", right.node);
        result.put("inferredType", resultType);
        return new TypedExpression(result, resultType);
    }

    private TypedExpression annotate(Map<String, Object> expression, String type) {
        Map<String, Object> result = copy(expression);
        result.put("inferredType", type);
        return new TypedExpression(result, type);
    }

    @SuppressWarnings("unchecked")
    private Map<String, TableSchema> readSnapshot(Map<String, Object> snapshot) {
        Object rawTables = snapshot.get("tables");
        if (!(rawTables instanceof List<?> list))
            throw fail("SEMANTIC_INVALID_CATALOG", "catalogSnapshot.tables 必须是数组", null);
        Map<String, TableSchema> result = new LinkedHashMap<>();
        for (Object rawTable : list) {
            if (!(rawTable instanceof Map<?, ?>))
                throw fail("SEMANTIC_INVALID_CATALOG", "Catalog 表定义必须是对象", null);
            Map<String, Object> table = (Map<String, Object>) rawTable;
            String name = normalize(string(table, "name"));
            if (result.containsKey(name))
                throw fail("SEMANTIC_INVALID_CATALOG", "Catalog 中表名重复：" + name, null);
            Object rawColumns = table.get("columns");
            if (!(rawColumns instanceof List<?> columns))
                throw fail("SEMANTIC_INVALID_CATALOG", "Catalog columns 必须是数组", null);
            List<ColumnSchema> schemas = new ArrayList<>();
            Set<String> names = new LinkedHashSet<>();
            for (Object rawColumn : columns) {
                if (!(rawColumn instanceof Map<?, ?>))
                    throw fail("SEMANTIC_INVALID_CATALOG", "Catalog 列定义必须是对象", null);
                Map<String, Object> column = (Map<String, Object>) rawColumn;
                String columnName = normalize(string(column, "name"));
                String type = string(column, "dataType").toUpperCase(Locale.ROOT);
                if (!names.add(columnName) || !TYPES.contains(type))
                    throw fail("SEMANTIC_INVALID_CATALOG", "Catalog 列重复或类型非法", null);
                schemas.add(new ColumnSchema(columnName, type));
            }
            result.put(name, new TableSchema(name, schemas));
        }
        return result;
    }

    private TableSchema requireTable(String name, Map<String, Object> node,
                                     Map<String, TableSchema> catalog) {
        TableSchema table = catalog.get(normalize(name));
        if (table == null) throw fail("SEMANTIC_TABLE_NOT_FOUND", "表不存在：" + name, node);
        return table;
    }

    private ColumnSchema requireColumn(TableSchema table, String name, Map<String, Object> node) {
        for (ColumnSchema column : table.columns()) if (column.name().equals(name)) return column;
        throw fail("SEMANTIC_COLUMN_NOT_FOUND", "列不存在：" + name, node);
    }

    private static String string(Map<String, Object> value, String key) {
        Object result = value.get(key);
        if (!(result instanceof String text))
            throw fail("SEMANTIC_INVALID_REQUEST", "字段必须是字符串：" + key, value);
        return text;
    }

    private static String normalize(String value) { return value.toLowerCase(Locale.ROOT); }
    private static Map<String, Object> copy(Map<String, Object> value) { return new LinkedHashMap<>(value); }

    private static SemanticFailure fail(String code, String message, Map<String, Object> node) {
        Integer line = null, column = null;
        if (node != null && node.get("loc") instanceof Map<?, ?> loc) {
            if (loc.get("line") instanceof Number number) line = number.intValue();
            if (loc.get("column") instanceof Number number) column = number.intValue();
        }
        return new SemanticFailure(new SemanticError("SEMANTIC", code, message, line, column, List.of()));
    }

    private record TypedExpression(Map<String, Object> node, String type) { }
    private static final class SemanticFailure extends RuntimeException {
        private final SemanticError error;
        private SemanticFailure(SemanticError error) { super(error.message()); this.error = error; }
    }
}

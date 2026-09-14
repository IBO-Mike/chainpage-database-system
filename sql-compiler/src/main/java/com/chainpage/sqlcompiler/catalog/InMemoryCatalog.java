package com.chainpage.sqlcompiler.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 供语义分析查询的线程安全内存 Catalog。 */
public final class InMemoryCatalog {
    private static final Set<String> DATA_TYPES = Set.of("INT", "VARCHAR");
    private final Map<String, TableSchema> tables = new LinkedHashMap<>();

    public synchronized CatalogResponse execute(CatalogRequest request) {
        if (request == null || request.op() == null) {
            return failure("CATALOG_INVALID_REQUEST", "请求必须包含 op");
        }
        return switch (request.op()) {
            case "create_table" -> createTable(request.table(), request.columns());
            case "find_table" -> findTable(request.table());
            case "find_column" -> findColumn(request.table(), request.column());
            case "snapshot" -> snapshot();
            default -> failure("CATALOG_UNKNOWN_OPERATION", "未知 Catalog 操作：" + request.op());
        };
    }

    private CatalogResponse createTable(String tableName, List<ColumnSchema> columns) {
        String table = normalizeName(tableName);
        if (table == null) return failure("CATALOG_INVALID_TABLE", "表名必须是合法的非空标识符");
        if (columns == null || columns.isEmpty())
            return failure("CATALOG_EMPTY_COLUMNS", "建表时列集合不得为空");
        if (tables.containsKey(table))
            return failure("CATALOG_TABLE_EXISTS", "表已存在：" + table);

        List<ColumnSchema> normalizedColumns = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (ColumnSchema column : columns) {
            if (column == null) return failure("CATALOG_INVALID_COLUMN", "列定义不得为 null");
            String name = normalizeName(column.name());
            if (name == null) return failure("CATALOG_INVALID_COLUMN", "列名必须是合法的非空标识符");
            if (!names.add(name)) return failure("CATALOG_DUPLICATE_COLUMN", "列名重复：" + name);
            String type = column.dataType() == null ? null : column.dataType().toUpperCase(Locale.ROOT);
            if (!DATA_TYPES.contains(type))
                return failure("CATALOG_UNKNOWN_TYPE", "未知数据类型：" + column.dataType());
            normalizedColumns.add(new ColumnSchema(name, type));
        }

        TableSchema schema = new TableSchema(table, normalizedColumns);
        tables.put(table, schema);
        return success("table", schema.toMap());
    }

    private CatalogResponse findTable(String tableName) {
        String table = normalizeName(tableName);
        if (table == null) return failure("CATALOG_INVALID_TABLE", "表名必须是合法的非空标识符");
        TableSchema schema = tables.get(table);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("found", schema != null);
        data.put("table", schema == null ? null : schema.toMap());
        return CatalogResponse.success(data);
    }

    private CatalogResponse findColumn(String tableName, String columnName) {
        String table = normalizeName(tableName);
        String column = normalizeName(columnName);
        if (table == null) return failure("CATALOG_INVALID_TABLE", "表名必须是合法的非空标识符");
        if (column == null) return failure("CATALOG_INVALID_COLUMN", "列名必须是合法的非空标识符");
        TableSchema schema = tables.get(table);
        ColumnSchema found = null;
        if (schema != null) {
            for (ColumnSchema candidate : schema.columns()) {
                if (candidate.name().equals(column)) {
                    found = candidate;
                    break;
                }
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("found", found != null);
        data.put("column", found == null ? null : found.toMap());
        return CatalogResponse.success(data);
    }

    private CatalogResponse snapshot() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tables", tables.values().stream().map(TableSchema::toMap).toList());
        return CatalogResponse.success(data);
    }

    private static String normalizeName(String value) {
        if (value == null || value.isEmpty() || !isIdentifierStart(value.charAt(0))) return null;
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!isIdentifierStart(c) && !(c >= '0' && c <= '9')) return null;
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static boolean isIdentifierStart(char value) {
        return value == '_' || value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z';
    }

    private static CatalogResponse success(String key, Object value) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(key, value);
        return CatalogResponse.success(data);
    }

    private static CatalogResponse failure(String code, String message) {
        return CatalogResponse.failure(new CatalogError("CATALOG", code, message, null, null));
    }
}

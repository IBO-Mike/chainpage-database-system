package com.chainpage.sqlcompiler.catalog;

import java.util.List;

public record CatalogRequest(String op, String table, List<ColumnSchema> columns, String column) {
    public static CatalogRequest createTable(String table, List<ColumnSchema> columns) {
        return new CatalogRequest("create_table", table, columns, null);
    }

    public static CatalogRequest findTable(String table) {
        return new CatalogRequest("find_table", table, null, null);
    }

    public static CatalogRequest findColumn(String table, String column) {
        return new CatalogRequest("find_column", table, null, column);
    }

    public static CatalogRequest snapshot() {
        return new CatalogRequest("snapshot", null, null, null);
    }
}

package com.chainpage.sqlcompiler.catalog;

import java.util.List;
import java.util.Map;

public final class InMemoryCatalogTest {
    private static int checks;

    public static void main(String[] args) {
        createsAndNormalizesSchema();
        findsTablesAndColumnsCaseInsensitively();
        returnsNotFoundAsSuccess();
        rejectsDuplicateTableAndColumns();
        rejectsEmptyColumnsAndUnknownTypes();
        returnsSnapshot();
        System.out.println("Catalog tests passed: " + checks);
    }

    @SuppressWarnings("unchecked")
    private static void createsAndNormalizesSchema() {
        InMemoryCatalog catalog = catalogWithStudent();
        Map<String, Object> table = (Map<String, Object>) catalog.execute(CatalogRequest.findTable("student")).data().get("table");
        check(table.get("name").equals("student"), "lowercase table");
        List<Map<String, Object>> columns = (List<Map<String, Object>>) table.get("columns");
        check(columns.get(0).get("name").equals("id"), "lowercase column");
        check(columns.get(1).get("dataType").equals("VARCHAR"), "uppercase type");
    }

    private static void findsTablesAndColumnsCaseInsensitively() {
        InMemoryCatalog catalog = catalogWithStudent();
        check((Boolean) catalog.execute(CatalogRequest.findTable("STUDENT")).data().get("found"), "find table");
        CatalogResponse column = catalog.execute(CatalogRequest.findColumn("sTuDeNt", "NAME"));
        check(column.ok() && (Boolean) column.data().get("found"), "find column");
    }

    private static void returnsNotFoundAsSuccess() {
        InMemoryCatalog catalog = catalogWithStudent();
        CatalogResponse table = catalog.execute(CatalogRequest.findTable("missing"));
        check(table.ok() && !(Boolean) table.data().get("found"), "missing table lookup succeeds");
        check(table.data().get("table") == null, "missing table is null");
        CatalogResponse column = catalog.execute(CatalogRequest.findColumn("missing", "id"));
        check(column.ok() && !(Boolean) column.data().get("found"), "missing column lookup succeeds");
    }

    private static void rejectsDuplicateTableAndColumns() {
        InMemoryCatalog catalog = catalogWithStudent();
        assertError(catalog.execute(CatalogRequest.createTable("STUDENT", List.of(new ColumnSchema("x", "INT")))),
                "CATALOG_TABLE_EXISTS");
        assertError(new InMemoryCatalog().execute(CatalogRequest.createTable("t", List.of(
                new ColumnSchema("ID", "INT"), new ColumnSchema("id", "INT")))),
                "CATALOG_DUPLICATE_COLUMN");
    }

    private static void rejectsEmptyColumnsAndUnknownTypes() {
        assertError(new InMemoryCatalog().execute(CatalogRequest.createTable("t", List.of())),
                "CATALOG_EMPTY_COLUMNS");
        assertError(new InMemoryCatalog().execute(CatalogRequest.createTable(
                "t", List.of(new ColumnSchema("id", "FLOAT")))), "CATALOG_UNKNOWN_TYPE");
    }

    @SuppressWarnings("unchecked")
    private static void returnsSnapshot() {
        CatalogResponse snapshot = catalogWithStudent().execute(CatalogRequest.snapshot());
        check(snapshot.ok(), "snapshot succeeds");
        List<Map<String, Object>> tables = (List<Map<String, Object>>) snapshot.data().get("tables");
        check(tables.size() == 1 && tables.get(0).get("name").equals("student"), "snapshot shape");
    }

    private static InMemoryCatalog catalogWithStudent() {
        InMemoryCatalog catalog = new InMemoryCatalog();
        CatalogResponse response = catalog.execute(CatalogRequest.createTable("Student", List.of(
                new ColumnSchema("ID", "int"), new ColumnSchema("Name", "varchar"))));
        check(response.ok(), "create succeeds");
        return catalog;
    }

    private static void assertError(CatalogResponse response, String code) {
        check(!response.ok(), "operation should fail");
        check(response.error().stage().equals("CATALOG"), "error stage");
        check(response.error().code().equals(code), "error code");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}

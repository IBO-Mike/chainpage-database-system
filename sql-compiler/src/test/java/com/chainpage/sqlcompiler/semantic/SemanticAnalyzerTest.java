package com.chainpage.sqlcompiler.semantic;

import com.chainpage.sqlcompiler.CompilerFrontEnd;
import com.chainpage.sqlcompiler.catalog.CatalogRequest;
import com.chainpage.sqlcompiler.catalog.ColumnSchema;
import com.chainpage.sqlcompiler.catalog.InMemoryCatalog;

import java.util.List;
import java.util.Map;

public final class SemanticAnalyzerTest {
    private static int checks;

    public static void main(String[] args) {
        connectsAllPreviousStagesAndAnnotatesExpressions();
        createIsVisibleOnlyInsideCompilation();
        rejectsMissingTableAndColumn();
        rejectsInsertProblems();
        rejectsInvalidBooleanExpressions();
        preservesEarlierStageErrors();
        System.out.println("Semantic Analyzer tests passed: " + checks);
    }

    @SuppressWarnings("unchecked")
    private static void connectsAllPreviousStagesAndAnnotatesExpressions() {
        AnalyzeResponse response = analyze("SELECT name FROM student WHERE age >= 18 AND name != 'Tom';", catalog());
        check(response.ok(), "full pipeline succeeds");
        Map<String, Object> where = (Map<String, Object>) response.statements().get(0).get("where");
        check(where.get("inferredType").equals("BOOL"), "AND is BOOL");
        Map<String, Object> comparison = (Map<String, Object>) where.get("left");
        check(comparison.get("inferredType").equals("BOOL"), "comparison is BOOL");
        Map<String, Object> identifier = (Map<String, Object>) comparison.get("left");
        check(identifier.get("inferredType").equals("INT"), "identifier type inferred");
        Map<String, Object> binding = (Map<String, Object>) identifier.get("binding");
        check(binding.get("table").equals("student") && binding.get("column").equals("age"), "binding attached");
        check(response.toMap().toString().contains("diagnostics=[]"), "empty diagnostics returned");
    }

    private static void createIsVisibleOnlyInsideCompilation() {
        InMemoryCatalog catalog = new InMemoryCatalog();
        AnalyzeResponse response = analyze(
                "CREATE TABLE temp(id INT);INSERT INTO temp(id) VALUES(1);SELECT id FROM temp;", catalog);
        check(response.ok() && response.statements().size() == 3, "later statements see temporary table");
        check(!(Boolean) catalog.execute(CatalogRequest.findTable("temp")).data().get("found"),
                "semantic analysis must not mutate persistent catalog");
    }

    private static void rejectsMissingTableAndColumn() {
        assertError(analyze("SELECT id FROM absent;", catalog()), "SEMANTIC_TABLE_NOT_FOUND");
        assertError(analyze("SELECT missing FROM student;", catalog()), "SEMANTIC_COLUMN_NOT_FOUND");
    }

    private static void rejectsInsertProblems() {
        assertError(analyze("INSERT INTO student(id,name) VALUES(1);", catalog()),
                "SEMANTIC_INSERT_ARITY_MISMATCH");
        assertError(analyze("INSERT INTO student(id,id) VALUES(1,2);", catalog()),
                "SEMANTIC_DUPLICATE_INSERT_COLUMN");
        assertError(analyze("INSERT INTO student(id) VALUES('wrong');", catalog()),
                "SEMANTIC_TYPE_MISMATCH");
    }

    private static void rejectsInvalidBooleanExpressions() {
        assertError(analyze("SELECT id FROM student WHERE age;", catalog()), "SEMANTIC_WHERE_NOT_BOOL");
        assertError(analyze("SELECT id FROM student WHERE age = '18';", catalog()),
                "SEMANTIC_COMPARISON_TYPE_MISMATCH");
        assertError(analyze("SELECT id FROM student WHERE NOT age;", catalog()),
                "SEMANTIC_OPERATOR_TYPE_MISMATCH");
    }

    private static void preservesEarlierStageErrors() {
        AnalyzeResponse lexer = analyze("SELECT @ FROM student;", catalog());
        check(!lexer.ok() && lexer.error().stage().equals("LEXER"), "lexer stage preserved");
        AnalyzeResponse parser = analyze("SELECT id student;", catalog());
        check(!parser.ok() && parser.error().stage().equals("PARSER"), "parser stage preserved");
    }

    private static InMemoryCatalog catalog() {
        InMemoryCatalog catalog = new InMemoryCatalog();
        catalog.execute(CatalogRequest.createTable("student", List.of(
                new ColumnSchema("id", "INT"), new ColumnSchema("name", "VARCHAR"),
                new ColumnSchema("age", "INT"))));
        return catalog;
    }

    private static AnalyzeResponse analyze(String sql, InMemoryCatalog catalog) {
        return new CompilerFrontEnd().analyzeSql(sql, catalog);
    }

    private static void assertError(AnalyzeResponse response, String code) {
        check(!response.ok(), "analysis should fail");
        check(response.error().stage().equals("SEMANTIC"), "semantic stage");
        check(response.error().code().equals(code), "error code: " + response.error().code());
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}

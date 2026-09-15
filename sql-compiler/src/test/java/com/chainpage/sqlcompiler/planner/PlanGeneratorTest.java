package com.chainpage.sqlcompiler.planner;

import com.chainpage.sqlcompiler.CompilerFrontEnd;
import com.chainpage.sqlcompiler.ast.JsonCodec;
import com.chainpage.sqlcompiler.catalog.CatalogRequest;
import com.chainpage.sqlcompiler.catalog.ColumnSchema;
import com.chainpage.sqlcompiler.catalog.InMemoryCatalog;
import com.chainpage.sqlcompiler.semantic.AnalyzeResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PlanGeneratorTest {
    private static int checks;

    public static void main(String[] args) {
        buildsAllStatementKindsInOrder();
        buildsSelectSchemaAndPredicate();
        expandsStarWithoutFilter();
        preservesExpressionsAndDoesNotShareInput();
        usesTemporaryTableSchemaWithoutChangingCatalog();
        rejectsInvalidRequestsAndMissingAnnotations();
        preservesEarlierStageErrors();
        System.out.println("Plan Generator tests passed: " + checks);
    }

    private static void buildsAllStatementKindsInOrder() {
        BuildPlanResponse response = new CompilerFrontEnd().buildPlanSql(
                "CREATE TABLE U(ID INT,Name VARCHAR);"
                        + "INSERT INTO U(Name,ID) VALUES('Alice',1);"
                        + "SELECT Name FROM U;DELETE FROM U WHERE ID=1;", new InMemoryCatalog());
        check(response.ok(), "four statements compile");
        check(response.plans().size() == 4, "plan count matches statement count");
        List<Map<String, Object>> plans = response.plans();
        check(plans.stream().map(plan -> plan.get("kind")).toList()
                .equals(List.of("CreateTable", "Insert", "Project", "Delete")), "plan order");
        check(plans.get(0).equals(Map.of("kind", "CreateTable", "table", "u",
                "columns", List.of(col("id", "INT"), col("name", "VARCHAR")),
                "children", List.of(), "schema", List.of())), "exact create plan");
        check(plans.get(1).get("columns").equals(List.of("name", "id")), "insert column order and lowercase");
        check(plans.get(1).get("table").equals("u"), "insert table lowercase");
        for (int index : List.of(0, 1, 3)) {
            check(plans.get(index).get("children").equals(List.of()), "write plans have no children");
            check(plans.get(index).get("schema").equals(List.of()), "write plans have empty schema");
        }
        check(map(plans.get(3).get("predicate")).get("inferredType").equals("BOOL"), "delete predicate typed");
        check(map(response.toMap().get("data")).get("plans").equals(plans), "response envelope");
        BuildPlanResponse delete = new CompilerFrontEnd().buildPlanSql("DELETE FROM t;", catalog());
        check(delete.ok() && delete.plans().get(0).containsKey("predicate")
                && delete.plans().get(0).get("predicate") == null, "delete without predicate is explicit null");
    }

    private static void buildsSelectSchemaAndPredicate() {
        BuildPlanResponse response = new CompilerFrontEnd().buildPlanSql(
                "SELECT Name,ID,Name FROM T WHERE NOT ID=1 OR Name='Tom';", catalog());
        check(response.ok(), "select compiles");
        Map<String, Object> project = response.plans().get(0);
        Map<String, Object> filter = child(project);
        Map<String, Object> scan = child(filter);
        check(project.get("kind").equals("Project") && filter.get("kind").equals("Filter")
                && scan.get("kind").equals("SeqScan"), "project-filter-scan order");
        check(project.get("columns").equals(List.of("name", "id", "name")), "projection order and duplicates");
        check(project.get("schema").equals(List.of(col("name", "VARCHAR"), col("id", "INT"),
                col("name", "VARCHAR"))), "projection types match order");
        check(scan.get("schema").equals(List.of(col("id", "INT"), col("name", "VARCHAR"))), "full scan schema");
        check(filter.get("schema").equals(scan.get("schema")), "filter inherits schema");
        check(scan.get("children").equals(List.of()) && scan.get("table").equals("t"), "scan leaf");
        check(map(filter.get("predicate")).get("operator").equals("OR"), "complete compound predicate");
    }

    private static void expandsStarWithoutFilter() {
        BuildPlanResponse response = new CompilerFrontEnd().buildPlanSql("SELECT * FROM t;", catalog());
        check(response.ok(), "star compiles");
        Map<String, Object> project = response.plans().get(0);
        Map<String, Object> scan = child(project);
        check(scan.get("kind").equals("SeqScan"), "no filter without WHERE");
        check(project.get("columns").equals(List.of("id", "name")), "star expands in catalog order");
        check(project.get("schema").equals(scan.get("schema")), "star schema equals scan schema");
    }

    private static void preservesExpressionsAndDoesNotShareInput() {
        AnalyzeResponse analyzed = new CompilerFrontEnd().analyzeSql(
                "INSERT INTO t(name,id) VALUES('Tom''s',1);SELECT id FROM t WHERE id=1;", catalog());
        String before = JsonCodec.stringify(analyzed.statements());
        BuildPlanResponse response = new PlanGenerator().buildPlan(new BuildPlanRequest(analyzed.statements()));
        check(response.ok(), "direct planner accepts semantic output");
        check(JsonCodec.stringify(analyzed.statements()).equals(before), "planning preserves input");
        check(response.plans().get(0).get("values").equals(analyzed.statements().get(0).get("values")),
                "insert expressions preserve every field and string content");
        Map<String, Object> predicate = map(child(response.plans().get(1)).get("predicate"));
        check(predicate.equals(analyzed.statements().get(1).get("where")), "predicate preserves every field");
        Map<String, Object> identifier = map(predicate.get("left"));
        check(identifier.get("binding").equals(Map.of("table", "t", "column", "id", "dataType", "INT")),
                "identifier binding retained");
        map(identifier.get("binding")).put("column", "changed");
        Map<String, Object> scan = child(child(response.plans().get(1)));
        map(((List<?>) scan.get("schema")).get(0)).put("name", "changed");
        check(JsonCodec.stringify(analyzed.statements()).equals(before), "nested plan mutation cannot alter AST");
        check(!child(response.plans().get(1)).get("schema").equals(scan.get("schema")),
                "schema lists are independent between plan nodes");
    }

    private static void usesTemporaryTableSchemaWithoutChangingCatalog() {
        InMemoryCatalog catalog = new InMemoryCatalog();
        String before = JsonCodec.stringify(catalog.execute(CatalogRequest.snapshot()).data());
        BuildPlanResponse response = new CompilerFrontEnd().buildPlanSql(
                "CREATE TABLE fresh(B VARCHAR,A INT);SELECT * FROM fresh;", catalog);
        check(response.ok(), "select sees temporary table");
        check(response.plans().get(1).get("columns").equals(List.of("b", "a")), "temporary schema order");
        check(JsonCodec.stringify(catalog.execute(CatalogRequest.snapshot()).data()).equals(before),
                "planning does not create persistent tables");
    }

    private static void rejectsInvalidRequestsAndMissingAnnotations() {
        PlanGenerator generator = new PlanGenerator();
        assertError(generator.buildPlan(null), "PLANNER_INVALID_REQUEST");
        assertError(generator.buildPlan(new BuildPlanRequest(null)), "PLANNER_INVALID_REQUEST");
        List<Map<String, Object>> nullStatement = new ArrayList<>();
        nullStatement.add(null);
        assertError(generator.buildPlan(new BuildPlanRequest(nullStatement)), "PLANNER_INVALID_REQUEST");
        BuildPlanResponse empty = generator.buildPlan(new BuildPlanRequest(List.of()));
        check(empty.ok() && empty.plans().isEmpty(), "empty batch succeeds");
        Map<String, Object> select = new LinkedHashMap<>(new CompilerFrontEnd()
                .analyzeSql("SELECT * FROM t;", catalog()).statements().get(0));
        select.remove("resolvedTable");
        assertError(generator.buildPlan(new BuildPlanRequest(List.of(select))), "PLANNER_MISSING_SCHEMA");
        Map<String, Object> insert = new CompilerFrontEnd()
                .parseSql("INSERT INTO t(id) VALUES(1);").statements().get(0);
        BuildPlanResponse missing = generator.buildPlan(new BuildPlanRequest(List.of(insert)));
        assertError(missing, "PLANNER_MISSING_ANNOTATION");
        check(missing.error().line() == 1 && missing.error().column() == 26, "error retains expression location");
        Map<String, Object> unsupported = Map.of("kind", "UpdateStmt", "table", "t");
        assertError(generator.buildPlan(new BuildPlanRequest(List.of(unsupported))), "PLANNER_UNSUPPORTED_STATEMENT");
        Map<String, Object> valid = new CompilerFrontEnd().analyzeSql("DELETE FROM t;", catalog()).statements().get(0);
        BuildPlanResponse partial = generator.buildPlan(new BuildPlanRequest(List.of(valid, unsupported)));
        check(!partial.ok() && partial.plans() == null && !partial.toMap().containsKey("data"),
                "failed batch does not expose partial plans");
    }

    private static void preservesEarlierStageErrors() {
        CompilerFrontEnd frontEnd = new CompilerFrontEnd();
        for (String[] entry : List.of(new String[]{"SELECT @ FROM t;", "LEXER"},
                new String[]{"SELECT id t;", "PARSER"}, new String[]{"SELECT missing FROM t;", "SEMANTIC"})) {
            BuildPlanResponse response = frontEnd.buildPlanSql(entry[0], catalog());
            check(!response.ok() && response.error().stage().equals(entry[1]), "upstream error stage " + entry[1]);
        }
    }

    private static InMemoryCatalog catalog() {
        InMemoryCatalog catalog = new InMemoryCatalog();
        catalog.execute(CatalogRequest.createTable("t", List.of(
                new ColumnSchema("id", "INT"), new ColumnSchema("name", "VARCHAR"))));
        return catalog;
    }

    private static Map<String, Object> col(String name, String type) {
        return Map.of("name", name, "dataType", type);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }

    private static Map<String, Object> child(Map<String, Object> plan) {
        List<?> children = (List<?>) plan.get("children");
        check(children.size() == 1, "unary plan has one child");
        return map(children.get(0));
    }

    private static void assertError(BuildPlanResponse response, String code) {
        check(!response.ok() && response.error().stage().equals("PLANNER")
                && response.error().code().equals(code), "expected error " + code);
        check(response.toMap().containsKey("error") && !response.toMap().containsKey("data"), "error envelope");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}

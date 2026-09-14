package com.chainpage.sqlcompiler.extension;

import com.chainpage.sqlcompiler.recovery.StatementRecovery;
import com.chainpage.sqlcompiler.semantic.*;
import com.chainpage.sqlcompiler.planner.*;
import com.chainpage.sqlcompiler.optimizer.Optimizer;
import com.chainpage.sqlcompiler.optimizer.OptimizeRequest;
import java.util.*;
import static com.chainpage.sqlcompiler.extension.SqlTree.*;

/** module-interfaces.md 的 compile JSON 边界；只使用调用方的 Catalog 副本。 */
public final class CompileService {
    public ExtensionResponse compile(Map<String, Object> request) {
        Object requestId = request == null ? null : request.get("requestId");
        if (request == null || !(requestId instanceof String id) || id.isBlank() || !(request.get("sql") instanceof String)
                || !(request.get("optimize") instanceof Boolean) || !(request.get("catalogSnapshot") instanceof Map<?, ?>))
            return failure(requestId instanceof String ? requestId : null, null, fail("PARSER", "INVALID_REQUEST", "需要 requestId、sql、catalogSnapshot、optimize", null).error);
        ExtensionResponse lexed = new ExtensionLexer().lex(node("sql", request.get("sql")));
        if (!lexed.ok()) return failure(requestId, null, lexed.error());
        return compileTokens((String)requestId, nodes(lexed.data().get("tokens")), map(request.get("catalogSnapshot")), (Boolean)request.get("optimize"));
    }
    public ExtensionResponse compileTokens(String requestId, List<Map<String, Object>> tokens, Map<String, Object> snapshot, boolean optimize) {
        Integer index = null;
        try {
            Map<String, Object> catalog = map(copy(snapshot));
            // 即使输入没有语句，也必须校验 Catalog，不能把错误快照当作空库。
            ExtensionResponse valid = new ExtensionSemanticAnalyzer().analyze(node("statements", List.of(), "catalogSnapshot", catalog));
            if (!valid.ok()) return failure(requestId, null, valid.error());
            List<StatementRecovery.Segment> segments = new StatementRecovery().segments(tokens);
            for (var segment : segments) if (!segment.parsed().ok()) return failure(requestId, segment.statementIndex(), segment.parsed().error());
            List<Map<String, Object>> result = new ArrayList<>();
            for (var segment : segments) {
                index = segment.statementIndex();
                ExtensionResponse parsed = segment.parsed();
                Map<String, Object> ast = nodes(parsed.data().get("statements")).get(0);
                boolean basic = !ast.containsKey("items") && !ast.containsKey("rows") && !"UpdateStmt".equals(ast.get("kind"))
                        && basicCatalog(catalog) && basicCreate(ast)
                        && new com.chainpage.sqlcompiler.ast.AstService().makeNode(new com.chainpage.sqlcompiler.ast.MakeNodeRequest(ast)).ok();
                Map<String, Object> annotated, plan;
                if (basic) {
                    AnalyzeResponse semantic = new SemanticAnalyzer().analyze(new AnalyzeRequest(List.of(ast), catalog));
                    if (!semantic.ok()) return failure(requestId, index, semantic.error().toMap());
                    annotated = semantic.statements().get(0);
                    BuildPlanResponse planned = new PlanGenerator().buildPlan(new BuildPlanRequest(List.of(annotated)));
                    if (!planned.ok()) return failure(requestId, index, planned.error().toMap());
                    plan = planned.plans().get(0);
                } else {
                    parsed = StatementRecovery.parseOne(segment.tokens(), true);
                    if (!parsed.ok()) return failure(requestId, index, parsed.error());
                    ast = nodes(parsed.data().get("statements")).get(0);
                    ExtensionResponse semantic = new ExtensionSemanticAnalyzer().analyze(node("statements", List.of(ast), "catalogSnapshot", catalog));
                    if (!semantic.ok()) return failure(requestId, index, semantic.error());
                    annotated = nodes(semantic.data().get("statements")).get(0);
                    ExtensionResponse planned = new ExtensionPlanner().buildPlan(semantic.data());
                    if (!planned.ok()) return failure(requestId, index, planned.error());
                    plan = nodes(planned.data().get("plans")).get(0);
                }
                Map<String, Object> optimized = null;
                if (optimize) {
                    var response = new Optimizer().optimize(new OptimizeRequest(plan));
                    if (!response.ok()) return failure(requestId, index, response.error().toMap());
                    optimized = response.optimizedPlan();
                }
                result.add(node("statementIndex", index, "tokens", copy(segment.tokens()), "ast", ast, "semantic", annotated, "plan", plan, "optimizedPlan", optimized));
                if ("CreateTable".equals(plan.get("kind"))) nodes(catalog.get("tables")).add(node("name", plan.get("table"), "columns", copy(plan.get("columns"))));
            }
            return new ExtensionResponse(true, node("requestId", requestId, "statements", result), null);
        } catch (Failure e) { return failure(requestId, index, e.error); }
        catch (IllegalArgumentException | ClassCastException | NullPointerException | IndexOutOfBoundsException e) {
            return failure(requestId, index, fail("PARSER", "INVALID_REQUEST", "编译输入结构无效", null).error);
        }
    }
    private static boolean basicCatalog(Map<String, Object> catalog) {
        for (Map<String, Object> table : nodes(catalog.get("tables"))) for (Map<String, Object> c : nodes(table.get("columns")))
            if (!Set.of("INT", "VARCHAR").contains(c.get("dataType")) || c.containsKey("nullable")) return false;
        return true;
    }
    private static boolean basicCreate(Map<String, Object> ast) {
        if (!"CreateTableStmt".equals(ast.get("kind"))) return true;
        for (Map<String, Object> c : nodes(ast.get("columns"))) if (!Set.of("INT", "VARCHAR").contains(c.get("dataType")) || c.containsKey("nullable")) return false;
        return true;
    }
    public static ExtensionResponse failure(Object requestId, Integer index, Map<String, Object> error) {
        return new ExtensionResponse(false, null, node("requestId", requestId, "statementIndex", index, "stage", error.get("stage"),
                "code", error.get("code"), "message", error.get("message"), "line", error.get("line"), "column", error.get("column"), "pageId", null));
    }
}

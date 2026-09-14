package com.chainpage.sqlcompiler.explain;

import com.chainpage.sqlcompiler.extension.ExtensionLexer;
import com.chainpage.sqlcompiler.extension.ExtensionResponse;

import java.util.List;
import java.util.Map;
import static com.chainpage.sqlcompiler.explain.ExplainData.*;

/** 只持有表结构快照，不持有执行器、数据行或存储连接。 */
public final class ExplainService {
    private final Map<String, Object> catalogSnapshot;
    public ExplainService(Map<String, Object> catalogSnapshot) { this.catalogSnapshot = map(copy(catalogSnapshot)); }

    public ExplainResponse explain(ExplainRequest request) {
        if (request == null || request.sql() == null)
            return ExplainResponse.failure(error("PARSER", "EXPLAIN_INVALID_REQUEST", "请求必须包含非 null 的 sql", null, List.of("EXPLAIN")));
        ExtensionResponse lexed = new ExtensionLexer().lex(Map.of("sql", request.sql()));
        if (!lexed.ok()) return ExplainResponse.failure(lexed.error());
        List<Map<String, Object>> tokens = nodes(copy(lexed.data().get("tokens")));
        Map<String, Object> first = tokens.get(0);
        if (!List.of("IDENTIFIER", "KEYWORD").contains(first.get("type")) || !"EXPLAIN".equalsIgnoreCase((String) first.get("lexeme")))
            return ExplainResponse.failure(error("PARSER", "EXPLAIN_EXPECTED_KEYWORD", "SQL 必须以 EXPLAIN 开始", first, List.of("EXPLAIN")));
        // 只移除 Parser 输入中的控制关键字；所有 Token 仍保留原 SQL 中的行列位置。
        first.put("type", "KEYWORD"); first.put("lexeme", "EXPLAIN");
        ExtensionResponse compiled = new com.chainpage.sqlcompiler.extension.CompileService().compileTokens(
                "explain", tokens.subList(1, tokens.size()), catalogSnapshot, true);
        if (!compiled.ok()) return ExplainResponse.failure(compiled.error());
        List<Map<String, Object>> statements = nodes(compiled.data().get("statements"));
        if (statements.size() != 1)
            return ExplainResponse.failure(error("PARSER", "EXPLAIN_STATEMENT_COUNT", "一次 EXPLAIN 必须且只能包含一条语句", first, List.of()));
        Map<String, Object> statement = statements.get(0);
        Map<String, Object> plan = map(statement.get("plan")), optimized = map(statement.get("optimizedPlan"));
        String tree;
        try { tree = new PlanTreeFormatter().format(optimized); }
        catch (IllegalArgumentException | ClassCastException | NullPointerException exception) {
            return ExplainResponse.failure(error("PLANNER", "PLANNER_INVALID_PLAN", "无法格式化计划：" + exception.getMessage(), null, List.of()));
        }
        return ExplainResponse.success(tokens, map(statement.get("ast")), map(statement.get("semantic")), plan, optimized, tree);
    }
}

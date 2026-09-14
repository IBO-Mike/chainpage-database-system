package com.chainpage.sqlcompiler;

import com.chainpage.sqlcompiler.lexer.LexRequest;
import com.chainpage.sqlcompiler.lexer.LexResponse;
import com.chainpage.sqlcompiler.lexer.Lexer;
import com.chainpage.sqlcompiler.parser.ParseRequest;
import com.chainpage.sqlcompiler.parser.ParseResponse;
import com.chainpage.sqlcompiler.parser.Parser;
import com.chainpage.sqlcompiler.catalog.CatalogRequest;
import com.chainpage.sqlcompiler.catalog.CatalogResponse;
import com.chainpage.sqlcompiler.catalog.InMemoryCatalog;
import com.chainpage.sqlcompiler.semantic.AnalyzeRequest;
import com.chainpage.sqlcompiler.semantic.AnalyzeResponse;
import com.chainpage.sqlcompiler.semantic.SemanticAnalyzer;
import com.chainpage.sqlcompiler.semantic.SemanticError;

import com.chainpage.sqlcompiler.planner.BuildPlanRequest;
import com.chainpage.sqlcompiler.planner.BuildPlanResponse;
import com.chainpage.sqlcompiler.planner.PlanGenerator;
import com.chainpage.sqlcompiler.planner.PlannerError;

import java.util.List;

public final class CompilerFrontEnd {
    /** 数据库系统使用的统一 JSON 编译入口。 */
    public com.chainpage.sqlcompiler.extension.ExtensionResponse compile(java.util.Map<String, Object> request) {
        return new com.chainpage.sqlcompiler.extension.CompileService().compile(request);
    }

    private final Lexer lexer = new Lexer();
    private final Parser parser = new Parser();
    private final SemanticAnalyzer semanticAnalyzer = new SemanticAnalyzer();

    public ParseResponse parseSql(String sql) {
        LexResponse lexed = lexer.lex(new LexRequest(sql));
        if (!lexed.ok()) {
            return ParseResponse.failure(new com.chainpage.sqlcompiler.parser.ParserError(
                    lexed.error().stage(), lexed.error().code(), lexed.error().message(),
                    lexed.error().line(), lexed.error().column(), lexed.error().expected()));
        }
        return parser.parse(new ParseRequest(lexed.tokens()));
    }

    public BuildPlanResponse buildPlanSql(String sql, InMemoryCatalog catalog) {
        AnalyzeResponse analyzed = analyzeSql(sql, catalog);
        if (!analyzed.ok()) {
            return BuildPlanResponse.failure(new PlannerError(
                    analyzed.error().stage(), analyzed.error().code(), analyzed.error().message(),
                    analyzed.error().line(), analyzed.error().column(), analyzed.error().expected()));
        }
        return new PlanGenerator().buildPlan(new BuildPlanRequest(analyzed.statements()));
    }

    public AnalyzeResponse analyzeSql(String sql, InMemoryCatalog catalog) {
        if (catalog == null) {
            return AnalyzeResponse.failure(new SemanticError("SEMANTIC", "SEMANTIC_INVALID_REQUEST",
                    "catalog 不得为 null", null, null, List.of()));
        }
        ParseResponse parsed = parseSql(sql);
        if (!parsed.ok()) {
            return AnalyzeResponse.failure(new SemanticError(
                    parsed.error().stage(), parsed.error().code(), parsed.error().message(),
                    parsed.error().line(), parsed.error().column(), parsed.error().expected()));
        }
        CatalogResponse snapshot = catalog.execute(CatalogRequest.snapshot());
        if (!snapshot.ok()) {
            return AnalyzeResponse.failure(new SemanticError(
                    snapshot.error().stage(), snapshot.error().code(), snapshot.error().message(),
                    snapshot.error().line(), snapshot.error().column(), List.of()));
        }
        return semanticAnalyzer.analyze(new AnalyzeRequest(parsed.statements(), snapshot.data()));
    }
}

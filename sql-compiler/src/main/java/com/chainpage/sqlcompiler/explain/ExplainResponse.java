package com.chainpage.sqlcompiler.explain;

import java.util.List;
import java.util.Map;

/** data 严格包含 tokens、ast、semantic、plan、optimizedPlan、tree 六个字段。 */
public record ExplainResponse(boolean ok, Map<String, Object> data, Map<String, Object> error) {
    static ExplainResponse success(List<Map<String, Object>> tokens, Map<String, Object> ast,
                                   Map<String, Object> semantic, Map<String, Object> plan,
                                   Map<String, Object> optimizedPlan, String tree) {
        return new ExplainResponse(true, Map.of("tokens", tokens, "ast", ast, "semantic", semantic,
                "plan", plan, "optimizedPlan", optimizedPlan, "tree", tree), null);
    }
    static ExplainResponse failure(Map<String, Object> error) { return new ExplainResponse(false, null, error); }
    public Map<String, Object> toMap() { return ok ? Map.of("ok", true, "data", data) : Map.of("ok", false, "error", error); }
}

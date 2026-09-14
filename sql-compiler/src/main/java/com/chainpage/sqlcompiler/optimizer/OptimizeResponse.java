package com.chainpage.sqlcompiler.optimizer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OptimizeResponse {
    private final Map<String, Object> originalPlan;
    private final Map<String, Object> optimizedPlan;
    private final List<String> appliedRules;
    private final OptimizerError error;

    private OptimizeResponse(Map<String, Object> originalPlan, Map<String, Object> optimizedPlan,
                             List<String> appliedRules, OptimizerError error) {
        this.originalPlan = originalPlan;
        this.optimizedPlan = optimizedPlan;
        this.appliedRules = List.copyOf(appliedRules);
        this.error = error;
    }

    public static OptimizeResponse success(Map<String, Object> originalPlan,
                                           Map<String, Object> optimizedPlan, List<String> appliedRules) {
        return new OptimizeResponse(originalPlan, optimizedPlan, appliedRules, null);
    }

    public static OptimizeResponse failure(OptimizerError error) {
        return new OptimizeResponse(null, null, List.of(), error);
    }

    public boolean ok() { return error == null; }
    public Map<String, Object> originalPlan() { return originalPlan; }
    public Map<String, Object> optimizedPlan() { return optimizedPlan; }
    public List<String> appliedRules() { return appliedRules; }
    public OptimizerError error() { return error; }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", ok());
        if (ok()) result.put("data", Map.of("originalPlan", originalPlan,
                "optimizedPlan", optimizedPlan, "appliedRules", appliedRules));
        else result.put("error", error.toMap());
        return result;
    }
}

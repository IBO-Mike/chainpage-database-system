package com.chainpage.sqlcompiler.planner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BuildPlanResponse {
    private final List<Map<String, Object>> plans;
    private final PlannerError error;

    private BuildPlanResponse(List<Map<String, Object>> plans, PlannerError error) {
        this.plans = plans == null ? null : List.copyOf(plans);
        this.error = error;
    }

    public static BuildPlanResponse success(List<Map<String, Object>> plans) {
        return new BuildPlanResponse(plans, null);
    }

    public static BuildPlanResponse failure(PlannerError error) {
        return new BuildPlanResponse(null, error);
    }

    public boolean ok() { return error == null; }
    public List<Map<String, Object>> plans() { return plans; }
    public PlannerError error() { return error; }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", ok());
        if (ok()) result.put("data", Map.of("plans", plans));
        else result.put("error", error.toMap());
        return result;
    }
}

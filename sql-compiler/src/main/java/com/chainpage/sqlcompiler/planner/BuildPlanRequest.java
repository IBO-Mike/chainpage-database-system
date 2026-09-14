package com.chainpage.sqlcompiler.planner;

import java.util.List;
import java.util.Map;

public record BuildPlanRequest(List<Map<String, Object>> statements) { }

package com.chainpage.sqlcompiler.optimizer;

import java.util.Map;

/** 对应 JSON 请求 {"plan": Plan}，plan 来自第 6 部分。 */
public record OptimizeRequest(Map<String, Object> plan) {}

package com.chainpage.sqlcompiler.explain;

/** 对应第 10 部分请求 {"sql":"EXPLAIN SELECT ...;"}。 */
public record ExplainRequest(String sql) {}

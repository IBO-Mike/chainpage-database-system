package com.chainpage.sqlcompiler.lexer;

/**
 * 词法分析请求体：待分析的 SQL 源码文本。
 *
 * @param sql SQL 源码字符串，不允许为 null
 */
public record LexRequest(String sql) {
}

package com.chainpage.sqlcompiler.parser;

import com.chainpage.sqlcompiler.lexer.Token;

import java.util.List;

/**
 * 语法分析请求体：上游 Lexer 产出的 Token 序列。
 *
 * @param tokens Token 列表，必须以且仅以一个 EOF 结尾（由 Parser.validate 校验）
 */
public record ParseRequest(List<Token> tokens) {
}

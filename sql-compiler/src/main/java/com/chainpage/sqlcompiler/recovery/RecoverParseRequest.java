package com.chainpage.sqlcompiler.recovery;

import com.chainpage.sqlcompiler.lexer.Token;

import java.util.List;

/** 对应第 8 部分的 JSON 请求 {"tokens": Token[]}。 */
public record RecoverParseRequest(List<Token> tokens) {}

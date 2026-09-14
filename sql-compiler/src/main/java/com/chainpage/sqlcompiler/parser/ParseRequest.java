package com.chainpage.sqlcompiler.parser;

import com.chainpage.sqlcompiler.lexer.Token;

import java.util.List;

public record ParseRequest(List<Token> tokens) {
}

package com.chainpage.sqlcompiler.recovery;

import com.chainpage.sqlcompiler.lexer.Token;
import com.chainpage.sqlcompiler.lexer.TokenType;
import com.chainpage.sqlcompiler.parser.ParseRequest;
import com.chainpage.sqlcompiler.parser.ParseResponse;
import com.chainpage.sqlcompiler.parser.Parser;
import com.chainpage.sqlcompiler.parser.ParserError;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 在分号或 EOF 处同步，按语句复用 LL(1) Parser，不保留错误语句的部分 AST。 */
public final class RecoveringParser {
    public RecoverParseResponse parse(RecoverParseRequest request) {
        RecoveryError invalid = validate(request);
        if (invalid != null) return RecoverParseResponse.failure(invalid);

        List<Token> tokens = request.tokens();
        List<Map<String, Object>> statements = new ArrayList<>();
        List<RecoveryError> errors = new ArrayList<>();
        Parser parser = new Parser();
        int start = 0;
        for (int cursor = 0; cursor < tokens.size(); cursor++) {
            Token token = tokens.get(cursor);
            boolean eof = token.type() == TokenType.EOF;
            if (!eof && !(token.type() == TokenType.DELIMITER && token.lexeme().equals(";"))) continue;
            if (eof && start == cursor) break; // 空文件或最后一个分号之后只有 EOF。

            List<Token> segment = new ArrayList<>(tokens.subList(start, cursor + 1));
            if (!eof) {
                // Parser 的独立输入需要 EOF；使用下一 Token 的真实位置，不改写任何源 Token。
                Token next = tokens.get(cursor + 1);
                segment.add(new Token(TokenType.EOF, "", next.line(), next.column()));
            }
            ParseResponse response = parser.parse(new ParseRequest(segment));
            if (response.ok()) statements.addAll(response.statements());
            else errors.add(recover(response.error(), segment));
            // 无论成功还是失败都消费本次分号，不重试错误片段，保证持续前进。
            start = cursor + 1;
        }
        return RecoverParseResponse.success(statements, errors);
    }

    private static RecoveryError recover(ParserError error, List<Token> segment) {
        String unexpected = null;
        for (Token token : segment) {
            if (Objects.equals(error.line(), token.line()) && Objects.equals(error.column(), token.column())) {
                unexpected = display(token);
                break;
            }
        }
        return new RecoveryError(error.code(), error.message(), error.line(), error.column(), unexpected, error.expected());
    }

    private static RecoveryError validate(RecoverParseRequest request) {
        if (request == null || request.tokens() == null)
            return invalid("请求必须包含 tokens 数组", null, List.of("EOF"));
        List<Token> tokens = request.tokens();
        if (tokens.isEmpty()) return invalid("Token 数组必须以 EOF 结束", null, List.of("EOF"));
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token == null || token.type() == null || token.lexeme() == null
                    || token.line() < 1 || token.column() < 1)
                return invalid("Token 字段不完整或位置不是正整数", token, List.of("有效 Token"));
            if (token.type() == TokenType.EOF && index != tokens.size() - 1)
                return invalid("EOF 只能出现一次且必须位于末尾", token, List.of("末尾唯一 EOF"));
            // 原 Parser 信任 Lexer 的字符串格式；独立入口先检查，避免截取引号时抛出异常。
            if (token.type() == TokenType.STRING_LITERAL && !quotedString(token.lexeme()))
                return invalid("STRING_LITERAL 必须保留完整引号和双单引号转义", token, List.of("STRING_LITERAL"));
        }
        Token last = tokens.get(tokens.size() - 1);
        if (last.type() != TokenType.EOF)
            return invalid("Token 数组必须以 EOF 结束", last, List.of("EOF"));
        return null;
    }

    private static boolean quotedString(String text) {
        if (text.length() < 2 || text.charAt(0) != '\'' || text.charAt(text.length() - 1) != '\'') return false;
        for (int index = 1; index < text.length() - 1; index++) {
            if (text.charAt(index) == '\'') {
                if (index + 1 >= text.length() - 1 || text.charAt(index + 1) != '\'') return false;
                index++;
            }
        }
        return true;
    }

    private static RecoveryError invalid(String message, Token token, List<String> expected) {
        Integer line = token != null && token.line() > 0 ? token.line() : null;
        Integer column = token != null && token.column() > 0 ? token.column() : null;
        return new RecoveryError("PARSER_INVALID_REQUEST", message, line, column,
                token == null ? null : display(token), expected);
    }

    private static String display(Token token) {
        return token.type() == TokenType.EOF ? "EOF" : token.lexeme();
    }
}

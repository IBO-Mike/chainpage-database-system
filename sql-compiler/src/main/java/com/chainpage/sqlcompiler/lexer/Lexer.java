package com.chainpage.sqlcompiler.lexer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 词法分析器（Lexer / Scanner）—— SQL 编译器的第一阶段。
 *
 * <p>职责：把输入的 SQL 源码字符串按字符扫描，切分成带位置信息的 Token 序列，
 * 供后续 LL(1) Parser 使用。识别的词法单元包括：</p>
 * <ul>
 *   <li>关键字（KEYWORD）：SELECT / FROM / WHERE 等，统一转为大写；</li>
 *   <li>标识符（IDENTIFIER）：字母或下划线开头，后接字母/数字/下划线；</li>
 *   <li>整数（INT_LITERAL）与字符串字面量（STRING_LITERAL，支持 '' 转义）；</li>
 *   <li>运算符（OPERATOR）：单字符 = > < + - * / 与双字符 != >= <=；</li>
 *   <li>分隔符（DELIMITER）：( ) , ；</li>
 *   <li>注释：-- 行注释 与 块注释 会被跳过，不产生 Token。</li>
 * </ul>
 *
 * <p>扫描方式为单遍手写扫描器：主循环根据当前字符派发到对应的扫描子程序。
 * 遇到非法字符、未闭合字符串/注释时立即失败，返回结构化的 LexerError。
 * 方法使用 synchronized 保证实例的线程安全（source/index 等为共享可变状态）。</p>
 */
public final class Lexer {
    /** 关键字集合：识别出的标识符若（大写后）命中此集合，则归类为 KEYWORD。 */
    private static final Set<String> KEYWORDS = Set.of(
            "SELECT", "FROM", "WHERE", "CREATE", "TABLE", "INSERT", "INTO",
            "VALUES", "DELETE", "INT", "VARCHAR", "AND", "OR", "NOT");
    /** 单字符运算符集合。双字符运算符（!= >= <=）在 scanSymbol 中优先匹配。 */
    private static final Set<Character> SINGLE_OPERATORS = Set.of('=', '>', '<', '+', '-', '*', '/');
    /** 分隔符集合：括号、逗号与语句结束分号。 */
    private static final Set<Character> DELIMITERS = Set.of('(', ')', ',', ';');

    private String source;   // 当前正在扫描的 SQL 源码
    private int index;       // 下一个待读字符的下标（游标）
    private int line;        // 当前行号，从 1 开始（用于错误定位）
    private int column;      // 当前列号，从 1 开始（用于错误定位）
    private final List<Token> tokens = new ArrayList<>();

    /**
     * 词法分析入口：把请求中的 SQL 文本扫描为 Token 序列（末尾追加 EOF）。
     *
     * @param request 含 sql 字段的请求对象，可为 null（此时返回参数错误）
     * @return 成功时携带全部 Token；失败时携带 LEXER_* 错误码与出错位置
     */
    public synchronized LexResponse lex(LexRequest request) {
        // 参数校验：请求对象和 sql 字符串都必须存在
        if (request == null || request.sql() == null) {
            return failure("LEXER_INVALID_REQUEST", "请求必须包含非 null 的 sql 字符串", null, null,
                    List.of("{\"sql\": string}"));
        }

        // 每次分析前重置扫描状态（游标回到开头，行/列归 1，清空旧 Token）
        source = request.sql();
        index = 0;
        line = 1;
        column = 1;
        tokens.clear();

        // 主循环：按当前字符把工作派发给具体的扫描子程序
        while (!atEnd()) {
            char current = peek();
            if (isWhitespace(current)) {
                advance();                       // 空白字符直接跳过
            } else if (current == '-' && peekNext() == '-') {
                skipLineComment();               // "-- ..." 行注释
            } else if (current == '/' && peekNext() == '*') {
                LexResponse error = skipBlockComment();  // /* ... */ 块注释
                if (error != null) {
                    return error;                // 注释未闭合 → 立即报错
                }
            } else if (isIdentifierStart(current)) {
                scanIdentifier();                // 标识符 / 关键字
            } else if (isAsciiDigit(current)) {
                scanInteger();                   // 整数字面量
            } else if (current == '\'') {
                LexResponse error = scanString(); // 字符串字面量
                if (error != null) {
                    return error;                // 字符串未闭合 → 立即报错
                }
            } else {
                LexResponse error = scanSymbol(); // 运算符 / 分隔符 / 非法字符
                if (error != null) {
                    return error;
                }
            }
        }

        // 扫描结束后补一个 EOF Token，作为 Parser 判断输入结束的哨兵
        tokens.add(new Token(TokenType.EOF, "", line, column));
        return LexResponse.success(tokens);
    }

    /**
     * 扫描标识符或关键字：首字符必为字母/下划线，之后连续消费标识符字符。
     * 词素大写后命中关键字集合则输出 KEYWORD（词素归一化为大写），否则输出 IDENTIFIER（保留原始大小写）。
     */
    private void scanIdentifier() {
        int start = index;          // 记录词素起点，便于最后 substring 截取
        int startLine = line;       // Token 位置取词素首字符所在行列
        int startColumn = column;
        advance();
        while (!atEnd() && isIdentifierPart(peek())) {
            advance();
        }
        String raw = source.substring(start, index);
        String normalized = raw.toUpperCase(Locale.ROOT);   // 关键字大小写不敏感
        if (KEYWORDS.contains(normalized)) {
            tokens.add(new Token(TokenType.KEYWORD, normalized, startLine, startColumn));
        } else {
            tokens.add(new Token(TokenType.IDENTIFIER, raw, startLine, startColumn));
        }
    }

    /** 扫描无符号整数字面量：连续消费 ASCII 数字，直到非数字字符为止。 */
    private void scanInteger() {
        int start = index;
        int startLine = line;
        int startColumn = column;
        while (!atEnd() && isAsciiDigit(peek())) {
            advance();
        }
        tokens.add(new Token(TokenType.INT_LITERAL, source.substring(start, index), startLine, startColumn));
    }

    /**
     * 扫描单引号字符串字面量，支持 SQL 风格的 '' 转义（'' 表示一个字面单引号）。
     *
     * @return 正常结束返回 null 并产出 Token；到达输入末尾仍未闭合则返回错误响应
     */
    private LexResponse scanString() {
        int start = index;
        int startLine = line;
        int startColumn = column;
        advance();                      // 消费起始的单引号
        while (!atEnd()) {
            if (peek() == '\'') {
                advance();              // 疑似结束引号
                if (!atEnd() && peek() == '\'') {
                    advance();          // 连续两个单引号 → 转义的单引号，继续扫描
                    continue;
                }
                // 真正的结束引号：整个词素（含两侧引号）作为 STRING_LITERAL
                tokens.add(new Token(TokenType.STRING_LITERAL, source.substring(start, index), startLine, startColumn));
                return null;
            }
            advance();
        }
        // 走到输入末尾都没有配对的结束引号 → 未闭合字符串
        return failure("LEXER_UNTERMINATED_STRING", "字符串字面量未闭合", startLine, startColumn,
                List.of("'"));
    }

    /**
     * 扫描运算符与分隔符。注意匹配顺序：先尝试双字符运算符（!= >= <=），
     * 再匹配单字符运算符，最后匹配分隔符；都不命中则为非法字符。
     *
     * @return 成功返回 null；遇到非法字符返回错误响应
     */
    private LexResponse scanSymbol() {
        int startLine = line;
        int startColumn = column;
        char current = peek();
        char next = peekNext();
        // 最长匹配原则：双字符运算符优先于单字符
        if ((current == '!' && next == '=') || (current == '>' && next == '=')
                || (current == '<' && next == '=')) {
            advance();
            advance();
            tokens.add(new Token(TokenType.OPERATOR, "" + current + next, startLine, startColumn));
            return null;
        }
        if (SINGLE_OPERATORS.contains(current)) {
            advance();
            tokens.add(new Token(TokenType.OPERATOR, String.valueOf(current), startLine, startColumn));
            return null;
        }
        if (DELIMITERS.contains(current)) {
            advance();
            tokens.add(new Token(TokenType.DELIMITER, String.valueOf(current), startLine, startColumn));
            return null;
        }
        // 既不是运算符也不是分隔符 → 词法层面的非法字符
        return failure("LEXER_ILLEGAL_CHARACTER", "非法字符：" + current, startLine, startColumn,
                List.of("标识符", "整数", "字符串", "运算符", "分隔符"));
    }

    /** 跳过 "--" 行注释：消费到行结束符（不含）为止，不产生 Token。 */
    private void skipLineComment() {
        advance();  // 消费第一个 '-'
        advance();  // 消费第二个 '-'
        while (!atEnd() && peek() != '\n' && peek() != '\r') {
            advance();
        }
    }

    /**
     * 跳过块注释：消费到星号-斜杠的注释结束符为止。
     *
     * @return 正常闭合返回 null；到达输入末尾仍未闭合返回错误响应
     */
    private LexResponse skipBlockComment() {
        int startLine = line;       // 记录注释起点，便于报错定位
        int startColumn = column;
        advance();
        advance();
        while (!atEnd()) {
            if (peek() == '*' && peekNext() == '/') {
                advance();          // 消费 '*'
                advance();          // 消费 '/'，注释结束
                return null;
            }
            advance();
        }
        return failure("LEXER_UNTERMINATED_COMMENT", "多行注释未闭合", startLine, startColumn,
                List.of("*/"));
    }

    /** 组装一个词法错误响应（阶段固定为 LEXER），供各扫描子程序复用。 */
    private LexResponse failure(String code, String message, Integer errorLine, Integer errorColumn,
                                List<String> expected) {
        return LexResponse.failure(new LexerError("LEXER", code, message, errorLine, errorColumn, expected));
    }

    /**
     * 前进一个字符并维护行/列号：\r\n、\r、\n 都视为换行（列号归 1、行号 +1），
     * 其余字符列号 +1。这是行/列位置信息唯一的维护点。
     */
    private char advance() {
        char value = source.charAt(index++);
        if (value == '\r') {
            if (!atEnd() && source.charAt(index) == '\n') {
                index++;            // \r\n 视为一次换行，多消费一个字符
            }
            line++;
            column = 1;
        } else if (value == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
        return value;
    }

    /** 前瞻当前字符（不消费）。调用前保证 !atEnd()。 */
    private char peek() {
        return source.charAt(index);
    }

    /** 前瞻下一个字符（不消费）；越界时返回 '\0'，方便与“双字符运算符第二位”比较。 */
    private char peekNext() {
        return index + 1 < source.length() ? source.charAt(index + 1) : '\0';
    }

    /** 游标是否已到达输入末尾。 */
    private boolean atEnd() {
        return index >= source.length();
    }

    /** 是否为空白字符（空格、制表符、换行等）。 */
    private static boolean isWhitespace(char value) {
        return value == ' ' || value == '\t' || value == '\n' || value == '\r' || value == '\f';
    }

    /** 标识符首字符：ASCII 字母或下划线。 */
    private static boolean isIdentifierStart(char value) {
        return isAsciiLetter(value) || value == '_';
    }

    /** 标识符后续字符：字母、数字或下划线。 */
    private static boolean isIdentifierPart(char value) {
        return isIdentifierStart(value) || isAsciiDigit(value);
    }

    /** 是否为 ASCII 字母（不含 Unicode 字母，SQL 标识符限定为 ASCII）。 */
    private static boolean isAsciiLetter(char value) {
        return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
    }

    /** 是否为 ASCII 数字 0-9。 */
    private static boolean isAsciiDigit(char value) {
        return value >= '0' && value <= '9';
    }
}

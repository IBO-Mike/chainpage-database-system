package com.chainpage.sqlcompiler.extension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import static com.chainpage.sqlcompiler.extension.SqlTree.*;

/**
 * 拓展版词法分析器——与基础 {@link com.chainpage.sqlcompiler.lexer.Lexer} 相互独立，
 * 面向扩展 SQL 方言（UPDATE / JOIN / GROUP BY / 聚合函数 / NULL / 布尔 / 日期等）。
 *
 * <p>与基础版的主要差异：</p>
 * <ul>
 *   <li>关键字集合大幅扩充（UPDATE、SET、JOIN、ORDER BY、COUNT/SUM/AVG/MIN/MAX 等）；</li>
 *   <li>新增 DECIMAL_LITERAL：整数后跟 .数字 即识别为小数字面量；</li>
 *   <li>运算符支持 &lt;&gt;（等价于 !=）；独立的 "!" 视为非法；</li>
 *   <li>Token 直接以 Map 形式表示（type/lexeme/line/column），并统一大写关键字词素。</li>
 * </ul>
 *
 * <p>本类为紧凑实现：真正的扫描逻辑在内部类 {@link Scanner} 中。</p>
 */
public final class ExtensionLexer {
    /** 扩展方言的关键字集合（大小写不敏感，命中即归一化为大写词素）。 */
    private static final Set<String> KEYWORDS = Set.of("CREATE", "TABLE", "INSERT", "INTO", "VALUES", "SELECT", "FROM",
            "WHERE", "DELETE", "UPDATE", "SET", "AS", "ORDER", "BY", "ASC", "DESC", "GROUP", "HAVING", "JOIN", "INNER",
            "LEFT", "OUTER", "ON", "AND", "OR", "NOT", "NULL", "IS", "TRUE", "FALSE", "INT", "BIGINT", "DECIMAL",
            "VARCHAR", "BOOL", "BOOLEAN", "DATE", "COUNT", "SUM", "AVG", "MIN", "MAX", "NULLS", "FIRST", "LAST",
            "RIGHT", "FULL", "CROSS", "DISTINCT", "LIMIT", "OFFSET", "UNION");

    /**
     * 词法分析入口：请求形如 {"sql": "..."}，成功返回 {"tokens": [...]}。
     * 错误经由 {@link ExtensionResponse#run} 统一捕获为失败响应。
     */
    public ExtensionResponse lex(Map<String, Object> request) {
        return ExtensionResponse.run("LEXER", () -> node("tokens", new Scanner((String) request.get("sql")).scan()));
    }

    /**
     * 单遍手写扫描器：与基础版思路一致（主循环按当前字符分派），但结构更紧凑。
     * 状态：source 为输入文本，index 游标，line/column 记录位置，tokens 为产出列表。
     */
    private static final class Scanner {
        final String source;
        final List<Map<String, Object>> tokens = new ArrayList<>();
        int index, line = 1, column = 1;
        Scanner(String source) { this.source = java.util.Objects.requireNonNull(source); }

        /** 前瞻 offset 处的字符（不消费）；越界返回 '\0'。 */
        char peek(int offset) { return index + offset < source.length() ? source.charAt(index + offset) : '\0'; }

        /** 消费一个字符并维护行列号（换行时行 +1、列归 1）。 */
        char next() {
            char c = source.charAt(index++);
            if (c == '\n') { line++; column = 1; } else column++;
            return c;
        }

        /**
         * 扫描主循环：空白与行注释直接跳过；块注释要求闭合；
         * 其余按首字符分派为标识符/关键字、整数/小数、字符串、分隔符、运算符。
         * 结束时追加 EOF 哨兵。词法错误以 Failure 异常抛出。
         */
        List<Map<String, Object>> scan() {
            while (index < source.length()) {
                if (Character.isWhitespace(peek(0))) { next(); continue; }              // 跳过空白
                if (peek(0) == '-' && peek(1) == '-') {                                  // "--" 行注释
                    while (index < source.length() && peek(0) != '\n') next();
                    continue;
                }
                int start = index, row = line, col = column;   // 记录词素起点与位置
                if (peek(0) == '/' && peek(1) == '*') {                                  // 块注释，必须闭合
                    next(); next();
                    while (index < source.length() && !(peek(0) == '*' && peek(1) == '/')) next();
                    if (index == source.length()) throw error("UNTERMINATED_COMMENT", "块注释未闭合", row, col);
                    next(); next(); continue;
                }
                String type;
                char c = next();
                if (Character.isLetter(c) || c == '_') {                                 // 标识符 / 关键字
                    while (Character.isLetterOrDigit(peek(0)) || peek(0) == '_') next();
                    type = KEYWORDS.contains(source.substring(start, index).toUpperCase(Locale.ROOT)) ? "KEYWORD" : "IDENTIFIER";
                } else if (c >= '0' && c <= '9') {                                       // 整数，可扩展为小数
                    while (peek(0) >= '0' && peek(0) <= '9') next();
                    type = "INT_LITERAL";
                    if (peek(0) == '.' && peek(1) >= '0' && peek(1) <= '9') {            // 数字.数字 → 小数字面量（拓展点）
                        type = "DECIMAL_LITERAL"; next();
                        while (peek(0) >= '0' && peek(0) <= '9') next();
                    }
                } else if (c == '\'') {                                                  // 字符串，'' 为转义单引号
                    boolean closed = false;
                    while (index < source.length()) {
                        if (next() == '\'') {
                            if (peek(0) == '\'') next(); else { closed = true; break; }
                        }
                    }
                    if (!closed) throw error("UNTERMINATED_STRING", "字符串未闭合", row, col);
                    type = "STRING_LITERAL";
                } else if ("(),;.".indexOf(c) >= 0) type = "DELIMITER";                  // 分隔符
                else if ("+-*/=<>!".indexOf(c) >= 0) {                                   // 运算符（含双字符）
                    if ((c == '<' || c == '>' || c == '!') && peek(0) == '=') next();    // != >= <=
                    else if (c == '<' && peek(0) == '>') next();                         // <>（拓展点，等价 !=）
                    else if (c == '!') throw error("ILLEGAL_CHARACTER", "! 后必须有 =", row, col);  // 独立 ! 非法
                    type = "OPERATOR";
                } else throw error("ILLEGAL_CHARACTER", "不支持的字符：" + c, row, col);
                String lexeme = source.substring(start, index);
                // 关键字词素归一化为大写；其余保留原文
                tokens.add(node("type", type, "lexeme", type.equals("KEYWORD") ? lexeme.toUpperCase(Locale.ROOT) : lexeme,
                        "line", row, "column", col));
            }
            tokens.add(node("type", "EOF", "lexeme", "", "line", line, "column", column));   // EOF 哨兵
            return tokens;
        }

        /** 构造携带位置的词法错误（阶段固定为 LEXER），以 Failure 异常形式抛出。 */
        Failure error(String code, String message, int row, int col) {
            return fail("LEXER", code, message, node("loc", node("line", row, "column", col)));
        }
    }
}

package com.chainpage.sqlcompiler.lexer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 词法单元（Token）：词法分析的最小产出单位。
 *
 * @param type   Token 类别（关键字/标识符/字面量/运算符/分隔符/EOF）
 * @param lexeme 原文词素；关键字已归一化为大写，EOF 的词素为空串
 * @param line   词素首字符所在行号，从 1 开始
 * @param column 词素首字符所在列号，从 1 开始
 */
public record Token(TokenType type, String lexeme, int line, int column) {
    /** 转为有序 Map，用于 API 响应的 JSON 序列化（保持 type/lexeme/line/column 字段顺序）。 */
    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", type.name());
        value.put("lexeme", lexeme);
        value.put("line", line);
        value.put("column", column);
        return value;
    }
}

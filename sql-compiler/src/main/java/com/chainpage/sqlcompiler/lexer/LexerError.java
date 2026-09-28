package com.chainpage.sqlcompiler.lexer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 词法错误信息：包含统一阶段标识、错误码、人类可读消息、出错位置与期望内容。
 *
 * @param stage    出错阶段，固定为 "LEXER"
 * @param code     错误码，如 LEXER_ILLEGAL_CHARACTER / LEXER_UNTERMINATED_STRING 等
 * @param message  错误描述
 * @param line     出错行号（可能为 null，如请求参数错误时）
 * @param column   出错列号（可能为 null）
 * @param expected 出错处期望的合法输入提示，用于错误诊断
 */
public record LexerError(
        String stage,
        String code,
        String message,
        Integer line,
        Integer column,
        List<String> expected) {

    /** 转为有序 Map，用于 API 响应的 JSON 序列化。 */
    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("stage", stage);
        value.put("code", code);
        value.put("message", message);
        value.put("line", line);
        value.put("column", column);
        value.put("expected", expected);
        return value;
    }
}

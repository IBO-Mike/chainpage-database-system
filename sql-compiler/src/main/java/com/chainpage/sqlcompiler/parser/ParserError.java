package com.chainpage.sqlcompiler.parser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 语法错误信息：包含统一阶段标识、错误码、消息、出错位置与期望 Token 集合。
 *
 * @param stage    出错阶段，固定为 "PARSER"
 * @param code     错误码：PARSER_UNEXPECTED_TOKEN / PARSER_INVALID_REQUEST / PARSER_INT_OUT_OF_RANGE
 * @param message  错误描述
 * @param line     出错行号（参数类错误可能为 null）
 * @param column   出错列号（参数类错误可能为 null）
 * @param expected 出错处期望的终结符集合，用于错误诊断
 */
public record ParserError(
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

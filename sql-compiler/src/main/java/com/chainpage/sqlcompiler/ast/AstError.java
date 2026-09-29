package com.chainpage.sqlcompiler.ast;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AST 错误信息：包含统一阶段标识、错误码、消息与出错位置。
 *
 * @param stage   出错阶段，固定为 "AST"
 * @param code    错误码：AST_INVALID_NODE（节点结构非法）
 * @param message 错误描述
 * @param line    出错行号（从节点 loc 提取，可能为 null）
 * @param column  出错列号（可能为 null）
 */
public record AstError(
        String stage,
        String code,
        String message,
        Integer line,
        Integer column) {

    /** 转为有序 Map，用于 API 响应的 JSON 序列化。 */
    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("stage", stage);
        value.put("code", code);
        value.put("message", message);
        value.put("line", line);
        value.put("column", column);
        return value;
    }
}

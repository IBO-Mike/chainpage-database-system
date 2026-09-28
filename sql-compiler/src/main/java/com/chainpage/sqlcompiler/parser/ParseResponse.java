package com.chainpage.sqlcompiler.parser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 语法分析响应体：成功与失败共用同一封装，二者互斥。
 * <ul>
 *   <li>成功：statements 为语义化 AST（每条语句一个 Map 节点），error 为 null；</li>
 *   <li>失败：statements 为 null，error 携带 PARSER_* 错误详情。</li>
 * </ul>
 */
public final class ParseResponse {
    private final List<Map<String, Object>> statements;
    private final ParserError error;

    private ParseResponse(List<Map<String, Object>> statements, ParserError error) {
        this.statements = statements == null ? null : List.copyOf(statements);
        this.error = error;
    }

    /** 构造成功响应：携带全部语句的 AST。 */
    public static ParseResponse success(List<Map<String, Object>> statements) {
        return new ParseResponse(statements, null);
    }

    /** 构造失败响应：仅携带语法错误信息。 */
    public static ParseResponse failure(ParserError error) {
        return new ParseResponse(null, error);
    }

    /** 本次分析是否成功（无错误即成功）。 */
    public boolean ok() {
        return error == null;
    }

    public List<Map<String, Object>> statements() {
        return statements;
    }

    public ParserError error() {
        return error;
    }

    /** 统一序列化为 {"ok":..., "data":{"statements":[...]}} 或 {"ok":false, "error":{...}} 结构。 */
    public Map<String, Object> toMap() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", ok());
        if (ok()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("statements", statements);
            response.put("data", data);
        } else {
            response.put("error", error.toMap());
        }
        return response;
    }
}

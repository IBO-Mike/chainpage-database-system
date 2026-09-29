package com.chainpage.sqlcompiler.ast;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AST 服务响应体：成功与失败共用同一封装。
 * <ul>
 *   <li>made：makeNode 成功 —— 携带归一化节点与 JSON 文本（includeJson = true）；</li>
 *   <li>parsed：parseNode 成功 —— 只携带归一化节点（JSON 已由调用方提供）；</li>
 *   <li>failure：校验失败 —— 只携带 AstError。</li>
 * </ul>
 */
public final class AstResponse {
    private final Map<String, Object> node;
    private final String json;
    private final boolean includeJson;
    private final AstError error;

    private AstResponse(Map<String, Object> node, String json, boolean includeJson, AstError error) {
        this.node = node;
        this.json = json;
        this.includeJson = includeJson;
        this.error = error;
    }

    /** 构造 makeNode 成功响应：节点 + 序列化 JSON。 */
    public static AstResponse made(Map<String, Object> node, String json) {
        return new AstResponse(node, json, true, null);
    }

    /** 构造 parseNode 成功响应：仅节点。 */
    public static AstResponse parsed(Map<String, Object> node) {
        return new AstResponse(node, null, false, null);
    }

    /** 构造失败响应：仅错误信息。 */
    public static AstResponse failure(AstError error) {
        return new AstResponse(null, null, false, error);
    }

    /** 本次请求是否成功（无错误即成功）。 */
    public boolean ok() { return error == null; }
    public Map<String, Object> node() { return node; }
    public String json() { return json; }
    public AstError error() { return error; }

    /** 统一序列化为 {"ok":..., "data":{"node":..., "json"?}} 或 {"ok":false, "error":{...}} 结构。 */
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", ok());
        if (ok()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("node", node);
            if (includeJson) data.put("json", json);
            result.put("data", data);
        } else {
            result.put("error", error.toMap());
        }
        return result;
    }
}

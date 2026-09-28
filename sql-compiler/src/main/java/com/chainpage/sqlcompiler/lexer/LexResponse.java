package com.chainpage.sqlcompiler.lexer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 词法分析响应体：成功与失败共用同一封装，二者互斥。
 * <ul>
 *   <li>成功：tokens 非空、error 为 null；</li>
 *   <li>失败：tokens 为 null、error 携带错误详情。</li>
 * </ul>
 * 构造为不可变对象（tokens 用 List.copyOf 防御性拷贝）。
 */
public final class LexResponse {
    private final List<Token> tokens;
    private final LexerError error;

    private LexResponse(List<Token> tokens, LexerError error) {
        this.tokens = tokens == null ? null : List.copyOf(tokens);
        this.error = error;
    }

    /** 构造成功响应：携带完整的 Token 序列。 */
    public static LexResponse success(List<Token> tokens) {
        return new LexResponse(tokens, null);
    }

    /** 构造失败响应：仅携带词法错误信息。 */
    public static LexResponse failure(LexerError error) {
        return new LexResponse(null, error);
    }

    /** 本次分析是否成功（无错误即成功）。 */
    public boolean ok() {
        return error == null;
    }

    public List<Token> tokens() {
        return tokens;
    }

    public LexerError error() {
        return error;
    }

    /** 统一序列化为 {"ok":..., "data":{"tokens":[...]}} 或 {"ok":false, "error":{...}} 结构。 */
    public Map<String, Object> toMap() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", ok());
        if (ok()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("tokens", tokens.stream().map(Token::toMap).toList());
            response.put("data", data);
        } else {
            response.put("error", error.toMap());
        }
        return response;
    }
}

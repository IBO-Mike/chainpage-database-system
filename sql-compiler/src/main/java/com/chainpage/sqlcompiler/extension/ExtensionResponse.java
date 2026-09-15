package com.chainpage.sqlcompiler.extension;

import java.util.Map;
import java.util.function.Supplier;

/** 所有扩展阶段均返回 JSON 兼容的 {ok,data} 或 {ok,error}。 */
public record ExtensionResponse(boolean ok, Map<String, Object> data, Map<String, Object> error) {
    public Map<String, Object> toMap() { return ok ? Map.of("ok", true, "data", data) : Map.of("ok", false, "error", error); }
    static ExtensionResponse run(String stage, Supplier<Map<String, Object>> action) {
        try { return new ExtensionResponse(true, action.get(), null); }
        catch (SqlTree.Failure failure) { return new ExtensionResponse(false, null, failure.error); }
        catch (IllegalArgumentException | ClassCastException | NullPointerException | IndexOutOfBoundsException exception) {
            return new ExtensionResponse(false, null, SqlTree.fail(stage, "INVALID_REQUEST", "输入结构或字段无效", null).error);
        }
    }
}

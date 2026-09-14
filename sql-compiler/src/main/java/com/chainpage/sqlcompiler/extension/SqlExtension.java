package com.chainpage.sqlcompiler.extension;

import java.util.Map;
import static com.chainpage.sqlcompiler.extension.SqlTree.*;

/** 兼容本地调用的便捷入口，复用统一 compile 流水线。 */
public final class SqlExtension {
    public ExtensionResponse compileSql(String sql, Map<String, Object> catalogSnapshot) {
        ExtensionResponse compiled = new CompileService().compile(node("requestId", "local", "sql", sql, "catalogSnapshot", catalogSnapshot, "optimize", false));
        if (!compiled.ok()) return compiled;
        return new ExtensionResponse(true, node("plans", nodes(compiled.data().get("statements")).stream().map(s -> s.get("plan")).toList()), null);
    }

    public ExtensionResponse executeSql(String sql, Map<String, Object> snapshot, ExtensionExecutor executor) {
        ExtensionResponse compiled = compileSql(sql, snapshot);
        if (!compiled.ok()) return compiled;
        ExtensionResponse capability = ExtensionResponse.run("EXECUTOR", () -> {
            for (Map<String, Object> plan : nodes(compiled.data().get("plans"))) ExecutionContract.check(plan, executor);
            return compiled.data();
        });
        return capability.ok() ? executor.execute(compiled.data()) : capability;
    }
}

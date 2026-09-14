package edu.csu.chainpage.engine.integration;

import com.chainpage.sqlcompiler.CompilerFrontEnd;
import com.chainpage.sqlcompiler.explain.ExplainRequest;
import com.chainpage.sqlcompiler.explain.ExplainService;
import com.chainpage.sqlcompiler.extension.ExtensionResponse;
import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.common.JsonCodec;
import edu.csu.chainpage.engine.contract.CompileRequest;
import edu.csu.chainpage.engine.contract.CompileResponse;
import edu.csu.chainpage.engine.contract.CompiledStatement;
import edu.csu.chainpage.engine.contract.SqlCompilerClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** 把真实 SQL 编译器的 JSON 契约转换成数据库引擎的客户端契约。 */
public final class CompilerModuleClient implements SqlCompilerClient {
    private static final Pattern EXPLAIN = Pattern.compile("(?is)^\\s*EXPLAIN\\s+.+");

    private final CompilerFrontEnd compiler = new CompilerFrontEnd();
    private final JsonCodec json = new JsonCodec();

    @Override
    public DbResult<CompileResponse> compile(CompileRequest request) {
        if (request == null || request.getCatalogSnapshot() == null) {
            return invalid(request == null ? null : request.getRequestId(), "编译请求或目录快照为空");
        }
        String requestId = request.getRequestId();
        try {
            Map<String, Object> snapshot = json.readObject(json.write(request.getCatalogSnapshot()));
            if (request.getSql() != null && EXPLAIN.matcher(request.getSql()).matches()) {
                return explain(request, snapshot);
            }
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("requestId", requestId);
            input.put("sql", request.getSql());
            input.put("catalogSnapshot", snapshot);
            input.put("optimize", request.isOptimize());
            ExtensionResponse response = compiler.compile(input);
            if (!response.ok()) {
                return DbResult.fail(error(response.error(), requestId));
            }
            CompileResponse converted = json.read(json.write(response.data()), CompileResponse.class);
            if (!Objects.equals(requestId, converted.getRequestId())) {
                return invalid(requestId, "编译器响应的 requestId 与请求不一致");
            }
            return DbResult.ok(converted);
        } catch (RuntimeException exception) {
            return invalid(requestId, "编译器响应无法转换：" + exception.getMessage());
        }
    }

    private DbResult<CompileResponse> explain(CompileRequest request, Map<String, Object> snapshot) {
        var response = new ExplainService(snapshot).explain(new ExplainRequest(request.getSql()));
        if (!response.ok()) {
            return DbResult.fail(error(response.error(), request.getRequestId()));
        }
        Map<String, Object> data = response.data();
        CompiledStatement statement = new CompiledStatement(
                0,
                data.get("tokens"),
                data.get("ast"),
                data.get("semantic"),
                data.get("plan"),
                data.get("optimizedPlan")
        );
        return DbResult.ok(new CompileResponse(request.getRequestId(), List.of(statement)));
    }

    private static DbError error(Map<String, Object> source, String requestId) {
        if (source == null || !(source.get("stage") instanceof String stage)
                || !(source.get("code") instanceof String code)
                || !(source.get("message") instanceof String message)) {
            return new DbError(requestId, null, "COMPILER", "COMPILER_INVALID_RESPONSE",
                    "编译器返回了无效错误对象", null, null, null);
        }
        return new DbError(
                requestId,
                integer(source.get("statementIndex")),
                stage,
                code,
                message,
                integer(source.get("line")),
                integer(source.get("column")),
                integer(source.get("pageId"))
        );
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static <T> DbResult<T> invalid(String requestId, String message) {
        return DbResult.fail(new DbError(requestId, null, "COMPILER", "COMPILER_INVALID_RESPONSE",
                message, null, null, null));
    }
}

package edu.csu.chainpage.engine.explain;

import edu.csu.chainpage.engine.api.CatalogSnapshotProvider;
import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.CatalogSnapshot;
import edu.csu.chainpage.engine.contract.CompileRequest;
import edu.csu.chainpage.engine.contract.CompileResponse;
import edu.csu.chainpage.engine.contract.CompiledStatement;
import edu.csu.chainpage.engine.contract.SqlCompilerClient;
import edu.csu.chainpage.engine.plan.PlanNode;
import edu.csu.chainpage.engine.plan.PlanParser;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

// 负责完成EXPLAIN请求的编译、计划解析和树形文本构造
public final class ExplainService {

    private static final Pattern EXPLAIN_PATTERN = Pattern.compile(
            "(?is)^EXPLAIN\\s+.+;\\s*$"
    ); // 规约支持的EXPLAIN输入形式

    private final CatalogSnapshotProvider snapshotProvider; // 目录快照提供器
    private final SqlCompilerClient compilerClient; // SQL编译器客户端
    private final PlanParser planParser; // 编译计划解析器
    private final PlanTreeFormatter formatter; // 计划树格式化器

    // 使用默认计划解析器和格式化器创建EXPLAIN服务
    public ExplainService(
            CatalogSnapshotProvider snapshotProvider,
            SqlCompilerClient compilerClient) {
        this(snapshotProvider, compilerClient, new PlanParser(), new PlanTreeFormatter());
    }

    // 使用指定依赖创建便于独立测试的EXPLAIN服务
    public ExplainService(
            CatalogSnapshotProvider snapshotProvider,
            SqlCompilerClient compilerClient,
            PlanParser planParser,
            PlanTreeFormatter formatter) {
        this.snapshotProvider = Objects.requireNonNull(snapshotProvider, "snapshotProvider cannot be null");
        this.compilerClient = Objects.requireNonNull(compilerClient, "compilerClient cannot be null");
        this.planParser = Objects.requireNonNull(planParser, "planParser cannot be null");
        this.formatter = Objects.requireNonNull(formatter, "formatter cannot be null");
    }

    // 编译并格式化EXPLAIN请求，但不把任何计划交给执行器
    public DbResult<ExplainResult> explain(String requestId, String sql) {
        DbResult<Void> validation = validateExplainInput(sql);
        if (!validation.isOk()) {
            return DbResult.fail(copyWithRequestId(validation.error(), requestId));
        }

        DbResult<CatalogSnapshot> snapshotResult = snapshotProvider.snapshot(requestId);
        if (!snapshotResult.isOk()) {
            return DbResult.fail(snapshotResult.error());
        }

        DbResult<CompileResponse> compileResult = compilerClient.compile(new CompileRequest(
                requestId,
                sql,
                snapshotResult.data(),
                true
        ));
        if (!compileResult.isOk()) {
            return DbResult.fail(compileResult.error());
        }

        DbResult<ExplainResult> result = buildResult(compileResult.data());
        return result.isOk()
                ? result
                : DbResult.fail(copyWithRequestId(result.error(), requestId));
    }

    // 验证输入是否为带目标语句且以分号结束的EXPLAIN请求
    public DbResult<Void> validateExplainInput(String sql) {
        if (sql == null || sql.isBlank()) {
            return DbResult.fail(DbError.invalidRequest(null, "EXPLAIN的sql不能为空"));
        }
        if (!EXPLAIN_PATTERN.matcher(sql.trim()).matches()) {
            return DbResult.fail(DbError.invalidRequest(
                    null,
                    "sql必须是EXPLAIN开头、包含目标语句并以分号结束的请求"
            ));
        }
        return DbResult.ok(null);
    }

    // 从唯一一条编译结果中解析原始计划和优化计划并构造返回值
    public DbResult<ExplainResult> buildResult(CompileResponse response) {
        if (response == null) {
            return invalidCompilerResponse("SQL编译器返回结果不能为null");
        }
        List<CompiledStatement> statements = response.getStatements();
        if (statements.size() != 1) {
            return invalidCompilerResponse("EXPLAIN必须只返回一条编译结果");
        }

        CompiledStatement statement = statements.get(0);
        DbResult<PlanNode> planResult = planParser.parse(statement.getPlan());
        if (!planResult.isOk()) {
            return DbResult.fail(planResult.error());
        }
        DbResult<PlanNode> optimizedPlanResult = planParser.parse(statement.getOptimizedPlan());
        if (!optimizedPlanResult.isOk()) {
            return DbResult.fail(optimizedPlanResult.error());
        }

        return DbResult.ok(new ExplainResult(
                statement.getTokens(),
                statement.getAst(),
                statement.getSemantic(),
                planResult.data(),
                optimizedPlanResult.data(),
                formatter.format(optimizedPlanResult.data())
        ));
    }

    // 创建SQL编译器响应结构错误
    private <T> DbResult<T> invalidCompilerResponse(String message) {
        return DbResult.fail(new DbError(
                null,
                null,
                "COMPILER",
                "COMPILER_INVALID_RESPONSE",
                message,
                null,
                null,
                null
        ));
    }

    // 为本模块生成的错误补充当前请求编号
    private DbError copyWithRequestId(DbError error, String requestId) {
        return new DbError(
                requestId,
                error.getStatementIndex(),
                error.getStage(),
                error.getCode(),
                error.getMessage(),
                error.getLine(),
                error.getColumn(),
                error.getPageId()
        );
    }
}

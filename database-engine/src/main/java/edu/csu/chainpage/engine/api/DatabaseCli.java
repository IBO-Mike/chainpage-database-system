package edu.csu.chainpage.engine.api;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.common.JsonCodec;
import edu.csu.chainpage.engine.common.JsonCodecException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

// 数据库系统的命令行入口
public final class DatabaseCli {

    private final DatabaseApi databaseApi; // 数据库业务入口
    private final JsonCodec jsonCodec; // JSON编解码器
    private final AtomicLong requestSequence = new AtomicLong(1); // 请求序号

    // 创建命令行入口
    public DatabaseCli(DatabaseApi databaseApi, JsonCodec jsonCodec) {
        this.databaseApi = databaseApi;
        this.jsonCodec = jsonCodec;
    }

    // 循环读取输入并输出JSON响应
    public void run(InputStream input, PrintStream output) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (isQuitCommand(line)) {
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }
                printResponse(output, handleLine(line));
            }
        } catch (IOException exception) {
            printResponse(output, DbResult.fail(DbError.invalidRequest(
                    nextRequestId(),
                    "CLI读取输入失败: " + exception.getMessage()
            )));
        }
    }

    // 判断输入是否为退出命令
    public boolean isQuitCommand(String input) {
        if (input == null) {
            return false;
        }
        String normalized = input.trim();
        return "quit".equalsIgnoreCase(normalized)
                || "exit".equalsIgnoreCase(normalized);
    }

    // 为一次CLI请求生成请求编号
    public String nextRequestId() {
        return "req-" + requestSequence.getAndIncrement();
    }

    // 将数据库结果输出为统一JSON包络
    public void printResponse(
            PrintStream output,
            DbResult<DatabaseResponse> response) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        DatabaseResponse domainResponse = response.isOk() ? response.data() : null;
        boolean partialFailure = domainResponse != null && domainResponse.getError() != null;
        envelope.put("ok", response.isOk() && !partialFailure);
        envelope.put(
                "data",
                partialFailure
                        ? Map.of("results", domainResponse.getResults())
                        : response.isOk() ? response.data() : null
        );
        envelope.put(
                "error",
                partialFailure ? domainResponse.getError() : response.isOk() ? null : response.error()
        );
        output.println(jsonCodec.write(envelope));
    }

    // 解析一行JSON请求并交给数据库API处理
    private DbResult<DatabaseResponse> handleLine(String line) {
        String requestId = nextRequestId();
        try {
            DatabaseRequest request = jsonCodec.read(line, DatabaseRequest.class);
            return databaseApi.handle(requestId, request);
        } catch (JsonCodecException exception) {
            return DbResult.fail(DbError.invalidRequest(
                    requestId,
                    exception.getMessage()
            ));
        }
    }
}

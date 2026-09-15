package edu.csu.chainpage.engine.api;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.common.JsonCodec;
import edu.csu.chainpage.engine.common.JsonCodecException;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

// 数据库系统的命令行入口
public final class DatabaseCli {

    private static final String PROMPT = "cpdbs> "; // 等待用户输入SQL时显示的提示符
    private static final String STARTUP_MESSAGE =
            "ChainPage 数据库系统已启动。输入 SQL 执行，输入 quit 或 exit 退出。";

    private final DatabaseApi databaseApi; // 数据库业务入口
    private final JsonCodec jsonCodec; // JSON编解码器
    private final CliOutputFormat outputFormat; // 命令行输出格式
    private final HumanReadableFormatter humanReadableFormatter; // 文本结果格式化器
    private final AtomicLong requestSequence = new AtomicLong(1); // 请求序号

    // 创建默认输出人类可读文本的命令行入口
    public DatabaseCli(DatabaseApi databaseApi, JsonCodec jsonCodec) {
        this(databaseApi, jsonCodec, CliOutputFormat.HUMAN);
    }

    // 创建可以明确选择文本或JSON输出格式的命令行入口
    public DatabaseCli(
            DatabaseApi databaseApi,
            JsonCodec jsonCodec,
            CliOutputFormat outputFormat) {
        this.databaseApi = databaseApi;
        this.jsonCodec = jsonCodec;
        this.outputFormat = Objects.requireNonNull(outputFormat, "outputFormat cannot be null");
        this.humanReadableFormatter = new HumanReadableFormatter();
    }

    // 循环读取输入并输出选定格式的响应
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

    // 读取并执行SQL文件；文件模式不显示交互式cpdbs>提示符
    public boolean runFile(Path file, PrintStream output) {
        Objects.requireNonNull(file, "file cannot be null");
        Objects.requireNonNull(output, "output cannot be null");
        try (InputStream input = Files.newInputStream(file)) {
            return runFile(input, output);
        } catch (IOException exception) {
            printResponse(output, DbResult.fail(DbError.invalidRequest(
                    nextRequestId(),
                    "读取SQL文件失败: " + exception.getMessage()
            )));
            return false;
        }
    }

    // 从输入流读取完整SQL文本并按语句顺序执行
    public boolean runFile(InputStream input, PrintStream output) {
        Objects.requireNonNull(input, "input cannot be null");
        Objects.requireNonNull(output, "output cannot be null");
        if (outputFormat == CliOutputFormat.HUMAN) {
            output.println(STARTUP_MESSAGE);
        }
        try {
            String source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            if (!source.isEmpty() && source.charAt(0) == '\uFEFF') {
                source = source.substring(1);
            }
            boolean allStatementsSucceeded = true;
            for (String statement : splitSqlStatements(source)) {
                if (isQuitStatement(statement)) {
                    break;
                }
                DbResult<?> response = handleLine(statement);
                printResponse(output, response);
                if (!isSuccessfulResponse(response)) {
                    allStatementsSucceeded = false;
                }
            }
            return allStatementsSucceeded;
        } catch (IOException exception) {
            printResponse(output, DbResult.fail(DbError.invalidRequest(
                    nextRequestId(),
                    "读取SQL文件失败: " + exception.getMessage()
            )));
            return false;
        }
    }

    // 将SQL文本拆成完整语句；字符串、单行注释和块注释中的分号不会结束语句
    static List<String> splitSqlStatements(String source) {
        Objects.requireNonNull(source, "source cannot be null");
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuoted = false;
        boolean lineComment = false;
        boolean blockComment = false;
        boolean hasSql = false;
        int sourceLine = 1;
        int pendingLinePrefix = 0;
        boolean linePrefixAdded = false;

        for (int index = 0; index < source.length(); index++) {
            char currentChar = source.charAt(index);
            char nextChar = index + 1 < source.length() ? source.charAt(index + 1) : '\0';

            // 语句切分后补回该语句在原文件中的前置换行，保留编译器错误的行号。
            if (!linePrefixAdded) {
                current.append("\n".repeat(pendingLinePrefix));
                linePrefixAdded = true;
            }

            if (lineComment) {
                current.append(currentChar);
                if (currentChar == '\n' || currentChar == '\r') {
                    lineComment = false;
                }
                if (currentChar == '\n') {
                    sourceLine++;
                }
                continue;
            }
            if (blockComment) {
                current.append(currentChar);
                if (currentChar == '*' && nextChar == '/') {
                    current.append(nextChar);
                    index++;
                    blockComment = false;
                }
                if (currentChar == '\n') {
                    sourceLine++;
                }
                continue;
            }
            if (singleQuoted) {
                current.append(currentChar);
                if (currentChar == '\'') {
                    if (nextChar == '\'') {
                        current.append(nextChar);
                        index++;
                    } else {
                        singleQuoted = false;
                    }
                }
                if (currentChar == '\n') {
                    sourceLine++;
                }
                continue;
            }
            if (currentChar == '-' && nextChar == '-') {
                current.append(currentChar).append(nextChar);
                index++;
                lineComment = true;
                continue;
            }
            if (currentChar == '/' && nextChar == '*') {
                current.append(currentChar).append(nextChar);
                index++;
                blockComment = true;
                continue;
            }
            if (currentChar == '\'') {
                current.append(currentChar);
                singleQuoted = true;
                hasSql = true;
                continue;
            }
            if (currentChar == ';') {
                current.append(currentChar);
                if (hasSql) {
                    statements.add(current.toString());
                }
                current.setLength(0);
                hasSql = false;
                pendingLinePrefix = sourceLine - 1;
                linePrefixAdded = false;
                continue;
            }

            current.append(currentChar);
            if (!Character.isWhitespace(currentChar)) {
                hasSql = true;
            }
            if (currentChar == '\n') {
                sourceLine++;
            }
        }

        // 未闭合块注释交给编译器报告错误，而完整注释不应产生空SQL请求。
        if (blockComment) {
            hasSql = true;
        }
        if (hasSql) {
            statements.add(current.toString());
        }
        return List.copyOf(statements);
    }

    // 使用系统终端启动JLine交互式命令，提供左右移动、历史记录和Ctrl+C取消当前输入
    public void runInteractive() {
        if (outputFormat == CliOutputFormat.JSON) {
            // JSON模式必须保持标准输出每行只有一个JSON响应，避免提示符污染脚本结果。
            run(System.in, new PrintStream(System.out, true, StandardCharsets.UTF_8));
            return;
        }

        if (System.console() == null) {
            // 输入被重定向或通过管道传入时不启用原始终端，避免回显控制字符和破坏批处理输出。
            PrintStream output = new PrintStream(System.out, true, StandardCharsets.UTF_8);
            output.println(STARTUP_MESSAGE);
            output.println("当前输入不是交互式终端，已使用普通输入模式。");
            run(System.in, output);
            return;
        }

        Terminal terminal;
        try {
            terminal = TerminalBuilder.builder()
                    .name("chainpage-db")
                    .system(true)
                    .encoding(StandardCharsets.UTF_8)
                    .build();
        } catch (IOException | RuntimeException exception) {
            // 没有可用TTY时仍打印启动状态，并退回可用于管道和重定向的普通输入模式。
            PrintStream output = new PrintStream(System.out, true, StandardCharsets.UTF_8);
            output.println(STARTUP_MESSAGE);
            output.println("命令行编辑功能不可用，已切换为普通输入模式：" + exception.getMessage());
            run(System.in, output);
            return;
        }
        runInteractiveTerminal(
                terminal,
                new PrintStream(terminal.output(), true, StandardCharsets.UTF_8)
        );
    }

    // 使用指定输入输出流启动JLine，便于测试和嵌入式调用
    public void runInteractive(InputStream input, PrintStream output) {
        Objects.requireNonNull(input, "input cannot be null");
        Objects.requireNonNull(output, "output cannot be null");
        if (outputFormat == CliOutputFormat.JSON) {
            // JSON模式必须保持标准输出每行只有一个JSON响应，避免提示符污染脚本结果。
            run(input, output);
            return;
        }

        Terminal terminal;
        try {
            terminal = TerminalBuilder.builder()
                    .name("chainpage-db")
                    .streams(input, output)
                    .encoding(StandardCharsets.UTF_8)
                    .build();
        } catch (IOException | RuntimeException exception) {
            // 极少数不支持终端控制的环境仍可以使用普通逐行输入完成数据库操作。
            output.println(STARTUP_MESSAGE);
            output.println("命令行编辑功能不可用，已切换为普通输入模式：" + exception.getMessage());
            run(input, output);
            return;
        }

        if (isDumbTerminal(terminal)) {
            try {
                terminal.close();
            } catch (IOException ignored) {
                // 普通输入回退不应因为终端关闭失败而中断。
            }
            output.println(STARTUP_MESSAGE);
            output.println("当前输入不是交互式终端，已使用普通输入模式。");
            run(input, output);
            return;
        }

        runInteractiveTerminal(terminal, output);
    }

    // 在已经创建的JLine终端上循环读取并执行用户命令
    private void runInteractiveTerminal(Terminal terminal, PrintStream output) {
        try (terminal) {
            PrintWriter terminalOutput = terminal.writer();
            terminalOutput.println(STARTUP_MESSAGE);
            terminalOutput.flush();
            LineReader lineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .appName("chainpage-db")
                    // SQL中的感叹号应作为普通字符处理，不参与历史事件展开。
                    .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                    .build();
            while (true) {
                String line;
                try {
                    line = lineReader.readLine(PROMPT);
                } catch (UserInterruptException exception) {
                    // Ctrl+C只取消当前行，不退出数据库进程。
                    terminalOutput.println();
                    terminalOutput.flush();
                    continue;
                } catch (EndOfFileException exception) {
                    // Ctrl+D或输入流结束表示退出交互式命令行。
                    terminalOutput.println();
                    terminalOutput.flush();
                    break;
                }

                if (isQuitCommand(line)) {
                    terminalOutput.println();
                    terminalOutput.flush();
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }
                printResponse(output, handleLine(line));
                output.flush();
            }
        } catch (IOException exception) {
            printResponse(output, DbResult.fail(DbError.invalidRequest(
                    nextRequestId(),
                    "CLI终端初始化或读取失败: " + exception.getMessage()
            )));
        }
    }

    // 判断JLine是否只能提供无光标能力的哑终端
    private boolean isDumbTerminal(Terminal terminal) {
        String type = terminal.getType();
        return type == null || type.equalsIgnoreCase(Terminal.TYPE_DUMB)
                || type.toLowerCase(java.util.Locale.ROOT).startsWith("dumb-");
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

    // 判断SQL文件中的退出语句，兼容quit、exit及其末尾分号
    private boolean isQuitStatement(String input) {
        String normalized = stripLeadingSqlComments(input).strip();
        if (isQuitCommand(normalized)) {
            return true;
        }
        return normalized.endsWith(";")
                && isQuitCommand(normalized.substring(0, normalized.length() - 1));
    }

    // 为一次CLI请求生成请求编号
    public String nextRequestId() {
        return "req-" + requestSequence.getAndIncrement();
    }

    // 将数据库结果输出为选定的文本或JSON格式
    public void printResponse(
            PrintStream output,
            DbResult<?> response) {
        Objects.requireNonNull(output, "output cannot be null");
        Objects.requireNonNull(response, "response cannot be null");
        if (outputFormat == CliOutputFormat.HUMAN) {
            output.println(humanReadableFormatter.format(response));
            return;
        }
        printJsonResponse(output, response);
    }

    // 将数据库结果输出为原有统一JSON包络，供脚本调用保持兼容
    private void printJsonResponse(
            PrintStream output,
            DbResult<?> response) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        DatabaseResponse domainResponse = response.isOk() && response.data() instanceof DatabaseResponse value
                ? value
                : null;
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

    // 判断文件批处理是否全部成功；部分执行失败也应让命令行返回非零状态
    private boolean isSuccessfulResponse(DbResult<?> response) {
        if (!response.isOk()) {
            return false;
        }
        return !(response.data() instanceof DatabaseResponse domainResponse)
                || domainResponse.getError() == null;
    }

    // 解析一行JSON请求并交给数据库API处理
    private DbResult<?> handleLine(String line) {
        String requestId = nextRequestId();
        try {
            if (!line.stripLeading().startsWith("{")) {
                return isExplainSql(line)
                        ? databaseApi.handleExplain(requestId, line)
                        : databaseApi.handle(requestId, new DatabaseRequest(line, "execute"));
            }
            Map<String, Object> fields = jsonCodec.readObject(line);
            Object sql = fields.get("sql");
            if (sql instanceof String text && isExplainSql(text)) {
                return databaseApi.handleExplain(requestId, text);
            }
            DatabaseRequest request = jsonCodec.read(line, DatabaseRequest.class);
            return databaseApi.handle(requestId, request);
        } catch (JsonCodecException exception) {
            return DbResult.fail(DbError.invalidRequest(
                    requestId,
                    exception.getMessage()
            ));
        }
    }

    // 判断SQL是否以独立的EXPLAIN关键字开头
    private boolean isExplainSql(String sql) {
        String normalized = stripLeadingSqlComments(sql);
        return normalized.regionMatches(true, 0, "EXPLAIN", 0, "EXPLAIN".length())
                && (normalized.length() == "EXPLAIN".length()
                || Character.isWhitespace(normalized.charAt("EXPLAIN".length()))
                || normalized.charAt("EXPLAIN".length()) == ';');
    }

    // 去掉SQL开头的空白、单行注释和完整块注释，便于识别EXPLAIN或退出命令
    private String stripLeadingSqlComments(String sql) {
        String remaining = Objects.requireNonNull(sql, "sql cannot be null");
        while (true) {
            remaining = remaining.stripLeading();
            if (remaining.startsWith("--")) {
                int lineEnd = remaining.indexOf('\n');
                if (lineEnd < 0) {
                    return "";
                }
                remaining = remaining.substring(lineEnd + 1);
                continue;
            }
            if (remaining.startsWith("/*")) {
                int commentEnd = remaining.indexOf("*/", 2);
                if (commentEnd < 0) {
                    return remaining;
                }
                remaining = remaining.substring(commentEnd + 2);
                continue;
            }
            return remaining;
        }
    }
}

package edu.csu.chainpage.engine.integration;

import edu.csu.chainpage.engine.api.CliOutputFormat;
import edu.csu.chainpage.engine.api.HumanReadableFormatter;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.common.JsonCodec;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Windows、macOS 和 Linux 共用的可执行 JAR 入口。 */
public final class ChainPageMain {
    private ChainPageMain() {
    }

    public static void main(String[] args) {
        PrintStream output = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream errorOutput = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        Path dataDirectory = Path.of("chainpage-data");
        Path sqlFile = null;
        CliOutputFormat outputFormat = CliOutputFormat.HUMAN;
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            if ("--help".equals(argument)) {
                if (args.length != 1) {
                    errorOutput.println("参数错误：--help不能和其他参数同时使用。运行 --help 查看用法。");
                    System.exit(2);
                    return;
                }
                printHelp(output);
                return;
            }
            if ("--data".equals(argument)) {
                if (index + 1 >= args.length) {
                    errorOutput.println("参数错误：--data需要一个目录路径。运行 --help 查看用法。");
                    System.exit(2);
                    return;
                }
                try {
                    dataDirectory = Path.of(args[++index]);
                } catch (RuntimeException exception) {
                    errorOutput.println("参数错误：数据目录路径无效：" + exception.getMessage());
                    System.exit(2);
                    return;
                }
                continue;
            }
            if ("--file".equals(argument)) {
                if (index + 1 >= args.length) {
                    errorOutput.println("参数错误：--file需要一个SQL文件路径。运行 --help 查看用法。");
                    System.exit(2);
                    return;
                }
                try {
                    sqlFile = Path.of(args[++index]);
                } catch (RuntimeException exception) {
                    errorOutput.println("参数错误：SQL文件路径无效：" + exception.getMessage());
                    System.exit(2);
                    return;
                }
                continue;
            }
            if ("--format".equals(argument)) {
                if (index + 1 >= args.length) {
                    errorOutput.println("参数错误：--format需要human或json。运行 --help 查看用法。");
                    System.exit(2);
                    return;
                }
                try {
                    outputFormat = CliOutputFormat.parse(args[++index]);
                } catch (IllegalArgumentException exception) {
                    errorOutput.println("参数错误：" + exception.getMessage());
                    System.exit(2);
                    return;
                }
                continue;
            }
            if ("--json".equals(argument)) {
                outputFormat = CliOutputFormat.JSON;
                continue;
            }
            errorOutput.println("参数错误。运行 --help 查看用法。");
            System.exit(2);
            return;
        }

        if (sqlFile != null && !Files.isRegularFile(sqlFile)) {
            errorOutput.println("参数错误：SQL文件不存在或不是普通文件：" + sqlFile);
            System.exit(2);
            return;
        }

        boolean fileCompleted = true;
        try (ChainPageDatabase database = new ChainPageDatabase(dataDirectory, outputFormat)) {
            if (sqlFile != null) {
                fileCompleted = database.cli().runFile(sqlFile, output);
            } else if (outputFormat == CliOutputFormat.HUMAN) {
                database.cli().runInteractive();
            } else {
                database.cli().run(System.in, output);
            }
        } catch (ChainPageDatabase.DatabaseStartupException exception) {
            if (outputFormat == CliOutputFormat.JSON) {
                errorOutput.println(new JsonCodec().write(Map.of("ok", false, "error", exception.error())));
            } else {
                errorOutput.println(new HumanReadableFormatter().format(
                        DbResult.fail(exception.error())
                ));
            }
            System.exit(1);
        } catch (RuntimeException exception) {
            errorOutput.println("数据库运行失败: " + exception.getMessage());
            System.exit(1);
        }
        if (!fileCompleted) {
            System.exit(1);
        }
    }

    // 打印跨平台命令行入口的使用说明
    private static void printHelp(PrintStream output) {
        output.println("用法: java -jar chainpage-db.jar [--data 数据目录] [--format human|json] [--file SQL文件]");
        output.println("默认输出: human（MySQL风格文本）");
        output.println("--format json  保留机器可读的JSON输出");
        output.println("--json         --format json的简写");
        output.println("--file 文件    按UTF-8读取并依次执行SQL文件中的语句，不显示cpdbs>提示符");
        output.println("标准输入: 每行一条 SQL，也支持 {\"sql\":\"...\",\"mode\":\"execute|compile\"} JSON 请求");
        output.println("简化启动: macOS/Linux使用 ./run.sh，Windows使用 run.cmd");
    }
}

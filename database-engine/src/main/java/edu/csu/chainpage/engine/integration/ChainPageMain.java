package edu.csu.chainpage.engine.integration;

import edu.csu.chainpage.engine.common.JsonCodec;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
        if (args.length == 1 && "--help".equals(args[0])) {
            output.println("用法: java -jar chainpage-db.jar [--data 数据目录]");
            output.println("标准输入: 每行一条 SQL，或每行一个 {\"sql\":\"...\",\"mode\":\"execute|compile\"} JSON 对象");
            return;
        }
        if (args.length == 2 && "--data".equals(args[0])) {
            dataDirectory = Path.of(args[1]);
        } else if (args.length != 0) {
            errorOutput.println("参数错误。运行 --help 查看用法。");
            System.exit(2);
            return;
        }

        try (ChainPageDatabase database = new ChainPageDatabase(dataDirectory)) {
            database.cli().run(System.in, output);
        } catch (ChainPageDatabase.DatabaseStartupException exception) {
            errorOutput.println(new JsonCodec().write(Map.of("ok", false, "error", exception.error())));
            System.exit(1);
        } catch (RuntimeException exception) {
            errorOutput.println("数据库运行失败: " + exception.getMessage());
            System.exit(1);
        }
    }
}

package com.chainpage.sqlcompiler;

import com.chainpage.sqlcompiler.ast.JsonCodec;
import com.chainpage.sqlcompiler.catalog.CatalogRequest;
import com.chainpage.sqlcompiler.catalog.InMemoryCatalog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public final class ConsoleApp {
    private static final java.nio.charset.Charset CONSOLE_CHARSET = StandardCharsets.UTF_8;

    private ConsoleApp() { }

    public static void main(String[] args) throws IOException {
        // 控制台输入、标准输出和错误输出统一使用 UTF-8。
        System.setOut(new PrintStream(System.out, true, CONSOLE_CHARSET));
        System.setErr(new PrintStream(System.err, true, CONSOLE_CHARSET));
        InMemoryCatalog catalog = new InMemoryCatalog();
        CompilerFrontEnd compiler = new CompilerFrontEnd();
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, CONSOLE_CHARSET));

        System.out.println("ChainPage SQL Compiler - Compile / Optimize / EXPLAIN");
        System.out.println("输入一行 SQL（每条语句必须以 ; 结束），输入 :help 查看命令。");
        while (true) {
            System.out.print("sql> ");
            String line = input.readLine();
            if (line == null || line.trim().equalsIgnoreCase(":quit")) break;
            if (line.trim().equalsIgnoreCase(":help")) {
                printHelp();
                continue;
            }
            if (line.trim().equalsIgnoreCase(":catalog")) {
                System.out.println(JsonCodec.stringify(catalog.execute(CatalogRequest.snapshot()).toMap()));
                continue;
            }
            if (line.isBlank()) continue;
            var snapshot = catalog.execute(CatalogRequest.snapshot()).data();
            Object response;
            var lexed = new com.chainpage.sqlcompiler.extension.ExtensionLexer().lex(java.util.Map.of("sql", line));
            @SuppressWarnings("unchecked")
            var tokens = lexed.ok() ? (java.util.List<java.util.Map<String, Object>>)lexed.data().get("tokens") : java.util.List.<java.util.Map<String,Object>>of();
            if (!tokens.isEmpty() && "EXPLAIN".equalsIgnoreCase((String)tokens.get(0).get("lexeme")))
                response = new com.chainpage.sqlcompiler.explain.ExplainService(snapshot).explain(new com.chainpage.sqlcompiler.explain.ExplainRequest(line)).toMap();
            else response = compiler.compile(java.util.Map.of("requestId", java.util.UUID.randomUUID().toString(),
                    "sql", line, "catalogSnapshot", snapshot, "optimize", true)).toMap();
            System.out.println(JsonCodec.stringify(response));
            System.out.println("提示：这里只编译 SQL，不执行语句，因此 CREATE 不会永久写入 Catalog。");
        }
        System.out.println("已退出。");
    }

    private static void printHelp() {
        System.out.println(":catalog  查看当前 Catalog 快照");
        System.out.println(":help     查看帮助");
        System.out.println(":quit     退出程序");
        System.out.println("空 Catalog 下可在同一行输入多条语句进行完整测试，例如：");
        System.out.println("CREATE TABLE student(id INT,name VARCHAR,age INT);"
                + "INSERT INTO student(id,name,age) VALUES(1,'Alice',18);"
                + "SELECT name FROM student WHERE age>=18;");
    }
}

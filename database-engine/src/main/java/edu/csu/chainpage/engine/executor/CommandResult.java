package edu.csu.chainpage.engine.executor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// 表示返回给数据库入口的用户可见命令结果
public final class CommandResult {

    private final String kind; // 结果种类
    private final List<String> columns; // 返回列名
    private final List<List<Object>> rows; // 返回二维行数据
    private final int affectedRows; // 受影响行数
    private final String message; // 面向用户的结果说明

    // 创建一个命令结果
    public CommandResult(
            String kind,
            List<String> columns,
            List<List<Object>> rows,
            int affectedRows,
            String message) {
        if (affectedRows < 0) {
            throw new IllegalArgumentException("affectedRows cannot be negative");
        }
        this.kind = Objects.requireNonNull(kind, "kind cannot be null");
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns cannot be null"));
        List<List<Object>> copiedRows = new ArrayList<>();
        for (List<Object> row : Objects.requireNonNull(rows, "rows cannot be null")) {
            copiedRows.add(List.copyOf(Objects.requireNonNull(row, "row cannot be null")));
        }
        this.rows = List.copyOf(copiedRows);
        this.affectedRows = affectedRows;
        this.message = Objects.requireNonNull(message, "message cannot be null");
    }

    // 创建建表成功结果
    public static CommandResult create() {
        return new CommandResult("CREATE", List.of(), List.of(), 0, "table created");
    }

    // 创建插入一条记录成功结果
    public static CommandResult insert() {
        return new CommandResult("INSERT", List.of(), List.of(), 1, "1 row inserted");
    }

    // 创建删除结果
    public static CommandResult delete(int count) {
        return new CommandResult(
                "DELETE",
                List.of(),
                List.of(),
                count,
                count == 1 ? "1 row deleted" : count + " rows deleted"
        );
    }

    // 创建查询结果
    public static CommandResult select(List<String> columns, List<List<Object>> rows) {
        List<List<Object>> safeRows = Objects.requireNonNull(rows, "rows cannot be null");
        return new CommandResult(
                "SELECT",
                columns,
                safeRows,
                0,
                safeRows.size() == 1
                        ? "1 row selected"
                        : safeRows.size() + " rows selected"
        );
    }

    // 获取结果种类
    public String getKind() {
        return kind;
    }

    // 以记录式访问方式获取结果种类
    public String kind() {
        return kind;
    }

    // 获取返回列名
    public List<String> getColumns() {
        return columns;
    }

    // 以记录式访问方式获取返回列名
    public List<String> columns() {
        return columns;
    }

    // 获取返回二维行数据
    public List<List<Object>> getRows() {
        return rows;
    }

    // 以记录式访问方式获取返回二维行数据
    public List<List<Object>> rows() {
        return rows;
    }

    // 获取受影响行数
    public int getAffectedRows() {
        return affectedRows;
    }

    // 以记录式访问方式获取受影响行数
    public int affectedRows() {
        return affectedRows;
    }

    // 获取结果说明
    public String getMessage() {
        return message;
    }

    // 以记录式访问方式获取结果说明
    public String message() {
        return message;
    }
}

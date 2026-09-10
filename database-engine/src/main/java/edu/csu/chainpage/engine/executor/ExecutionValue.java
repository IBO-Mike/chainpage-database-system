package edu.csu.chainpage.engine.executor;

import edu.csu.chainpage.engine.storage.RowSet;

import java.util.Objects;

// 表示执行计划在节点之间传递的两种结果：内部行集或最终命令结果
public final class ExecutionValue {

    private final RowSet rowSet; // 行集结果
    private final CommandResult commandResult; // 命令结果

    private ExecutionValue(RowSet rowSet, CommandResult commandResult) {
        this.rowSet = rowSet;
        this.commandResult = commandResult;
    }

    // 创建一个内部行集结果
    public static ExecutionValue rows(RowSet rowSet) {
        return new ExecutionValue(Objects.requireNonNull(rowSet, "rowSet cannot be null"), null);
    }

    // 创建一个最终命令结果
    public static ExecutionValue command(CommandResult result) {
        return new ExecutionValue(null, Objects.requireNonNull(result, "result cannot be null"));
    }

    // 判断当前结果是否是内部行集
    public boolean isRowSet() {
        return rowSet != null;
    }

    // 获取内部行集结果；当前结果不是行集时返回null
    public RowSet rowSet() {
        return rowSet;
    }

    // 获取最终命令结果；当前结果不是命令结果时返回null
    public CommandResult commandResult() {
        return commandResult;
    }
}

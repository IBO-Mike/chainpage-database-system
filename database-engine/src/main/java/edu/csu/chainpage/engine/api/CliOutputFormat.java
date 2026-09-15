package edu.csu.chainpage.engine.api;

import java.util.Locale;

// 表示命令行结果的显示格式
public enum CliOutputFormat {
    // 面向普通用户的文本格式，默认使用该格式
    HUMAN,
    // 面向脚本和程序调用方的JSON格式
    JSON;

    // 把命令行参数转换为显示格式
    public static CliOutputFormat parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("输出格式不能为空，只能使用human或json");
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "human", "text", "table" -> HUMAN;
            case "json" -> JSON;
            default -> throw new IllegalArgumentException(
                    "不支持的输出格式: " + value + "，只能使用human或json"
            );
        };
    }
}

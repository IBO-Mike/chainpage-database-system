package edu.csu.chainpage.engine.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// 验证命令行输出格式参数的解析规则
class CliOutputFormatTest {

    @Test
    void parsesHumanAndJsonNames() {
        assertEquals(CliOutputFormat.HUMAN, CliOutputFormat.parse("human"));
        assertEquals(CliOutputFormat.HUMAN, CliOutputFormat.parse("TABLE"));
        assertEquals(CliOutputFormat.JSON, CliOutputFormat.parse(" json "));
    }

    @Test
    void rejectsUnknownFormat() {
        assertThrows(IllegalArgumentException.class, () -> CliOutputFormat.parse("xml"));
    }
}

package edu.csu.chainpage.engine.storage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证INT和VARCHAR记录的稳定编码及输入校验
class PageRecordCodecTest {

    private final PageRecordCodec codec = new PageRecordCodec();
    private final TableSchema schema = new TableSchema(
            "student",
            List.of(
                    new ColumnSchema("id", "INT"),
                    new ColumnSchema("name", "VARCHAR")
            )
    );

    @Test
    void encodesAndDecodesIntAndVarcharInSchemaOrder() {
        Row row = new Row(Map.of("name", "Alice", "id", 1));

        var encoded = codec.encode(schema, row);
        var decoded = codec.decode(schema, encoded.data());

        assertTrue(encoded.isOk());
        assertTrue(decoded.isOk());
        assertEquals(Map.of("id", 1, "name", "Alice"), decoded.data().values());
        assertEquals(encoded.data().length, codec.encodedLength(schema, row));
    }

    @Test
    void rejectsMissingExtraAndWrongTypeValues() {
        assertFalse(codec.validateRow(schema, new Row(Map.of("id", 1))).isOk());
        assertFalse(codec.validateRow(schema, new Row(Map.of(
                "id", 1,
                "name", "Alice",
                "age", 18
        ))).isOk());
        var wrongType = codec.validateRow(schema, new Row(Map.of("id", "1", "name", "Alice")));

        assertFalse(wrongType.isOk());
        assertEquals("ROW_TYPE_MISMATCH", wrongType.error().getCode());
    }

    @Test
    void rejectsMalformedRecordBytes() {
        var result = codec.decode(schema, new byte[]{0, 0, 0, 1});

        assertFalse(result.isOk());
        assertEquals("INVALID_RECORD", result.error().getCode());
    }
}

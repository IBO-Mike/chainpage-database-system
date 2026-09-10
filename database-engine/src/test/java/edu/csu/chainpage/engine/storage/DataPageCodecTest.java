package edu.csu.chainpage.engine.storage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证数据页格式、槽位追加和逻辑删除
class DataPageCodecTest {

    private final PageRecordCodec recordCodec = new PageRecordCodec();
    private final DataPageCodec pageCodec = new DataPageCodec();
    private final TableSchema schema = new TableSchema(
            "student",
            List.of(new ColumnSchema("id", "INT"), new ColumnSchema("name", "VARCHAR"))
    );

    @Test
    void encodesReadsAndMarksOnePageRecord() {
        DataPage page = new DataPage();
        byte[] record = recordCodec.encode(
                schema,
                new Row(Map.of("id", 1, "name", "Alice"))
        ).data();
        RowId rowId = pageCodec.append(page, record, 7).data();

        String encodedPage = pageCodec.encode(page).data();
        DataPage decodedPage = pageCodec.decode(encodedPage).data();
        var liveRows = pageCodec.readLiveRows(decodedPage, schema, 7, recordCodec);

        assertEquals(new RowId(7, 0), rowId);
        assertTrue(liveRows.isOk());
        assertEquals(1, liveRows.data().size());
        assertEquals("Alice", liveRows.data().get(0).values().valueOf("name"));

        var deleted = pageCodec.markDeleted(decodedPage, List.of(rowId), 7);
        assertTrue(deleted.isOk());
        assertEquals(1, deleted.data());
        assertTrue(pageCodec.readLiveRows(decodedPage, schema, 7, recordCodec).data().isEmpty());
    }

    @Test
    void rejectsRecordWhenPageHasNoSpace() {
        DataPage page = new DataPage();
        var result = pageCodec.append(
                page,
                new byte[DataPage.PAGE_SIZE],
                1
        );

        assertFalse(result.isOk());
        assertEquals("PAGE_FULL", result.error().getCode());
    }

    @Test
    void decodesFreshZeroFilledPageAsEmpty() {
        String zeroPage = java.util.Base64.getEncoder().encodeToString(new byte[DataPage.PAGE_SIZE]);

        var result = pageCodec.decode(zeroPage);

        assertTrue(result.isOk());
        assertTrue(result.data().slots().isEmpty());
    }
}

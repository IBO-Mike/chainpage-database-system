package edu.csu.chainpage.engine.storage;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

// 负责把内存页编解码为页式存储系统使用的Base64字符串
public final class DataPageCodec {

    // 页格式版本标记，避免把不兼容的字节误当成记录页
    private static final int MAGIC = 0x43475031;

    // 解码一页固定长度的Base64数据
    public DbResult<DataPage> decode(String base64Page) {
        if (base64Page == null) {
            return failure("INVALID_PAGE_DATA", "页数据不能为null", null);
        }

        final byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Page);
        } catch (IllegalArgumentException exception) {
            return failure("INVALID_PAGE_DATA", "页数据不是合法的Base64字符串", null);
        }
        if (bytes.length != DataPage.PAGE_SIZE) {
            return failure("INVALID_PAGE_SIZE", "页数据解码后必须是4096字节", null);
        }

        ByteBuffer input = ByteBuffer.wrap(bytes);
        int magic = input.getInt();
        int slotCount = input.getInt();
        if (magic == 0 && slotCount == 0) {
            return DbResult.ok(new DataPage());
        }
        if (magic != MAGIC) {
            return failure("INVALID_PAGE_FORMAT", "页格式版本标记不正确", null);
        }
        if (slotCount < 0 || slotCount > DataPage.PAGE_SIZE / DataPage.SLOT_HEADER_SIZE) {
            return failure("INVALID_PAGE_FORMAT", "页槽数量不合法", null);
        }

        List<PageSlot> slots = new ArrayList<>(slotCount);
        for (int index = 0; index < slotCount; index++) {
            if (input.remaining() < DataPage.SLOT_HEADER_SIZE) {
                return failure("INVALID_PAGE_FORMAT", "页槽头信息不完整", null);
            }
            int deletedFlag = Byte.toUnsignedInt(input.get());
            if (deletedFlag != 0 && deletedFlag != 1) {
                return failure("INVALID_PAGE_FORMAT", "页槽删除标记不合法", null);
            }
            int length = input.getInt();
            if (length < 0 || length > input.remaining()) {
                return failure("INVALID_PAGE_FORMAT", "页槽记录长度不合法", null);
            }
            byte[] record = new byte[length];
            input.get(record);
            slots.add(new PageSlot(record, deletedFlag == 1));
        }
        return DbResult.ok(new DataPage(slots));
    }

    // 把内存页编码为解码后恰好4096字节的Base64字符串
    public DbResult<String> encode(DataPage page) {
        if (page == null) {
            return failure("INVALID_PAGE", "页不能为null", null);
        }
        if (page.freeBytes() < 0) {
            return failure("PAGE_OVERFLOW", "页内容超出4096字节", null);
        }

        ByteBuffer output = ByteBuffer.allocate(DataPage.PAGE_SIZE);
        output.putInt(MAGIC);
        List<PageSlot> slots = page.slots();
        output.putInt(slots.size());
        for (PageSlot slot : slots) {
            byte[] record = slot.getData();
            if (output.remaining() < DataPage.SLOT_HEADER_SIZE + record.length) {
                return failure("PAGE_OVERFLOW", "页内容超出4096字节", null);
            }
            output.put((byte) (slot.isDeleted() ? 1 : 0));
            output.putInt(record.length);
            output.put(record);
        }
        return DbResult.ok(Base64.getEncoder().encodeToString(output.array()));
    }

    // 向页中追加已经编码的记录并返回其物理位置
    public DbResult<RowId> append(DataPage page, byte[] encodedRow, int pageId) {
        if (page == null) {
            return failure("INVALID_PAGE", "页不能为null", pageId);
        }
        if (encodedRow == null) {
            return failure("INVALID_RECORD", "记录字节不能为null", pageId);
        }
        if (pageId < 0) {
            return failure("INVALID_PAGE_ID", "页号不能为负数", pageId);
        }
        if (!page.canFit(encodedRow.length)) {
            return failure("PAGE_FULL", "页空间不足", pageId);
        }

        int slotId = page.slots().size();
        page.appendRecord(encodedRow);
        return DbResult.ok(new RowId(pageId, slotId));
    }

    // 读取页中所有尚未删除的记录
    public DbResult<List<InternalRow>> readLiveRows(
            DataPage page,
            TableSchema schema,
            int pageId,
            PageRecordCodec recordCodec) {
        if (page == null || schema == null || recordCodec == null) {
            return failure("INVALID_ARGUMENT", "读取页记录的参数不能为null", pageId);
        }
        if (pageId < 0) {
            return failure("INVALID_PAGE_ID", "页号不能为负数", pageId);
        }

        List<InternalRow> rows = new ArrayList<>();
        List<PageSlot> slots = page.slots();
        for (int slotId = 0; slotId < slots.size(); slotId++) {
            PageSlot slot = slots.get(slotId);
            if (slot.isDeleted()) {
                continue;
            }
            DbResult<Row> decoded = recordCodec.decode(schema, slot.getData());
            if (!decoded.isOk()) {
                return failure(
                        decoded.error().getCode(),
                        decoded.error().getMessage(),
                        pageId
                );
            }
            rows.add(new InternalRow(new RowId(pageId, slotId), decoded.data()));
        }
        return DbResult.ok(List.copyOf(rows));
    }

    // 只标记属于当前页且被调用方指定的记录
    public DbResult<Integer> markDeleted(DataPage page, List<RowId> rowIds, int pageId) {
        if (page == null || rowIds == null) {
            return failure("INVALID_ARGUMENT", "删除页记录的参数不能为null", pageId);
        }
        if (pageId < 0) {
            return failure("INVALID_PAGE_ID", "页号不能为负数", pageId);
        }

        int deletedCount = 0;
        for (RowId rowId : rowIds) {
            if (rowId == null) {
                return failure("INVALID_ROW_ID", "记录标识不能为null", pageId);
            }
            if (rowId.pageId() != pageId) {
                return failure("ROW_ID_PAGE_MISMATCH", "记录标识不属于当前页", pageId);
            }
            if (rowId.slotId() >= page.slots().size()) {
                return failure("ROW_NOT_FOUND", "记录槽不存在", pageId);
            }
            PageSlot slot = page.slots().get(rowId.slotId());
            if (!slot.isDeleted()) {
                page.deleteSlot(rowId.slotId());
                deletedCount++;
            }
        }
        return DbResult.ok(deletedCount);
    }

    // 创建统一格式的页编解码错误
    private <T> DbResult<T> failure(String code, String message, Integer pageId) {
        return DbResult.fail(new DbError(
                null,
                null,
                "STORAGE",
                code,
                message,
                null,
                null,
                pageId
        ));
    }
}

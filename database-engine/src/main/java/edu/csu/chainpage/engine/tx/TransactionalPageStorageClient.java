package edu.csu.chainpage.engine.tx;

import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.AllocatedPage;
import edu.csu.chainpage.engine.contract.DropTablePagesResult;
import edu.csu.chainpage.engine.contract.FlushResult;
import edu.csu.chainpage.engine.contract.PageData;
import edu.csu.chainpage.engine.contract.PageStorageClient;
import edu.csu.chainpage.engine.contract.TablePages;
import edu.csu.chainpage.engine.contract.WritePageResult;

import java.util.Map;
import java.util.Objects;

// 在页式存储客户端外记录事务写入前镜像
public final class TransactionalPageStorageClient implements PageStorageClient {

    private final PageStorageClient delegate; // 实际执行页操作的客户端
    private final ThreadLocal<TransactionContext> currentContext = new ThreadLocal<>(); // 当前线程事务上下文

    // 创建事务感知的页式存储客户端
    public TransactionalPageStorageClient(PageStorageClient delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
    }

    // 为当前线程安装正在执行的事务上下文
    void activate(TransactionContext context) {
        if (currentContext.get() != null) {
            throw new IllegalStateException("transaction context is already active");
        }
        currentContext.set(Objects.requireNonNull(context, "context cannot be null"));
    }

    // 清除当前线程的事务上下文
    void deactivate() {
        currentContext.remove();
    }

    // 使用底层客户端写回全部页前镜像
    DbResult<Void> restoreBeforeImages(String requestId, Map<Integer, String> beforeImages) {
        for (Map.Entry<Integer, String> entry : beforeImages.entrySet()) {
            DbResult<WritePageResult> restored = delegate.writePage(
                    requestId,
                    entry.getKey(),
                    entry.getValue()
            );
            if (!restored.isOk()) {
                return DbResult.fail(restored.error());
            }
        }
        return DbResult.ok(null);
    }

    // 读取指定数据页
    @Override
    public DbResult<PageData> getPage(String requestId, int pageId) {
        return delegate.getPage(requestId, pageId);
    }

    // 首次写页前保存原始页内容，再转交底层客户端写入
    @Override
    public DbResult<WritePageResult> writePage(String requestId, int pageId, String base64Data) {
        TransactionContext context = currentContext.get();
        if (context != null && !context.pageBeforeImages().containsKey(pageId)) {
            DbResult<PageData> before = delegate.getPage(requestId, pageId);
            if (!before.isOk()) {
                return DbResult.fail(before.error());
            }
            context.recordPageBeforeWrite(pageId, before.data().getData());
        }
        return delegate.writePage(requestId, pageId, base64Data);
    }

    // 转交创建表页映射操作
    @Override
    public DbResult<TablePages> createTablePages(String requestId, String table) {
        return delegate.createTablePages(requestId, table);
    }

    // 转交删除表页映射操作
    @Override
    public DbResult<DropTablePagesResult> dropTablePages(String requestId, String table) {
        return delegate.dropTablePages(requestId, table);
    }

    // 转交表数据页分配操作
    @Override
    public DbResult<AllocatedPage> allocatePageForTable(String requestId, String table) {
        return delegate.allocatePageForTable(requestId, table);
    }

    // 转交表页映射查询操作
    @Override
    public DbResult<TablePages> listTablePages(String requestId, String table) {
        return delegate.listTablePages(requestId, table);
    }

    // 转交全部脏页刷新操作
    @Override
    public DbResult<FlushResult> flushAll(String requestId) {
        return delegate.flushAll(requestId);
    }
}

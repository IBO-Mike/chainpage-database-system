package edu.csu.chainpage.engine.lifecycle;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.CatalogSnapshot;
import edu.csu.chainpage.engine.contract.PageStorageClient;

import java.util.Objects;

// 管理数据库启动、就绪和关闭状态
public final class DatabaseLifecycle {

    private final CatalogLoader catalogLoader; // 系统目录加载器
    private final RecoveryService recoveryService; // 数据库重启恢复服务
    private final PageStorageClient pageStorageClient; // 页式存储客户端
    private boolean ready; // 当前是否可以接受SQL请求

    // 创建数据库生命周期管理器
    public DatabaseLifecycle(
            CatalogLoader catalogLoader,
            PageStorageClient pageStorageClient) {
        this.catalogLoader = Objects.requireNonNull(catalogLoader, "catalogLoader cannot be null");
        this.recoveryService = null;
        this.pageStorageClient = Objects.requireNonNull(pageStorageClient, "pageStorageClient cannot be null");
    }

    // 创建启动时会验证全部持久化表数据的生命周期管理器
    public DatabaseLifecycle(
            RecoveryService recoveryService,
            PageStorageClient pageStorageClient) {
        this.catalogLoader = null;
        this.recoveryService = Objects.requireNonNull(recoveryService, "recoveryService cannot be null");
        this.pageStorageClient = Objects.requireNonNull(pageStorageClient, "pageStorageClient cannot be null");
    }

    // 加载目录并使数据库进入就绪状态
    public synchronized DbResult<StartupResult> startup(String requestId) {
        ready = false;
        if (recoveryService != null) {
            DbResult<java.util.List<edu.csu.chainpage.engine.contract.TableSchema>> recoveryResult =
                    recoveryService.recover(requestId);
            if (!recoveryResult.isOk()) {
                return DbResult.fail(recoveryResult.error());
            }
            ready = true;
            return DbResult.ok(new StartupResult(recoveryResult.data(), true));
        }

        DbResult<CatalogSnapshot> loadResult = catalogLoader.load(requestId);
        if (!loadResult.isOk()) {
            return DbResult.fail(loadResult.error());
        }

        CatalogSnapshot snapshot = loadResult.data();
        ready = true;
        return DbResult.ok(new StartupResult(snapshot.getTables(), true));
    }

    // 刷新全部脏页并关闭数据库
    public synchronized DbResult<ShutdownResult> shutdown(String requestId) {
        DbResult<edu.csu.chainpage.engine.contract.FlushResult> flushResult =
                pageStorageClient.flushAll(requestId);
        if (!flushResult.isOk()) {
            return DbResult.fail(flushResult.error());
        }

        ready = false;
        return DbResult.ok(new ShutdownResult(
                flushResult.data().getFlushedPageIds(),
                true
        ));
    }

    // 获取数据库当前是否已经就绪
    public synchronized boolean isReady() {
        return ready;
    }

    // 将数据库标记为不可用
    public synchronized void markNotReady() {
        ready = false;
    }

    // 检查数据库是否已经就绪
    public synchronized DbResult<Void> requireReady(String requestId) {
        if (!ready) {
            return DbResult.fail(new DbError(
                    requestId,
                    null,
                    "LIFECYCLE",
                    "DATABASE_NOT_READY",
                    "数据库尚未启动或已经关闭",
                    null,
                    null,
                    null
            ));
        }
        return DbResult.ok(null);
    }
}

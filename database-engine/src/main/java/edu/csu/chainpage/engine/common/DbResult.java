package edu.csu.chainpage.engine.common;

import java.util.Objects;

// 数据库统一的操作结果包装类，同时表示成功结果或者是失败结果
public final class DbResult<T> {
    private final boolean ok; // 成功状态字段
    private final T data; // 数据字段
    private final DbError error; // 错误字段

    private DbResult(boolean ok, T data, DbError error) {
        this.ok = ok;
        this.data = data;
        this.error = error;
    }

    // 创建成功结果
    public static <T> DbResult<T> ok(T data) {
        return new DbResult<>(true, data, null);
    }

    // 创建失败结果
    public static <T> DbResult<T> fail(DbError error) {
        return new DbResult<>(false, null, Objects.requireNonNull(error, "error cannot be null"));
    }

    // 判断操作是否成功
    public boolean isOk() {
        return ok;
    }

    // 取得成功时的数据
    public T data() {
        return data;
    }

    // 取得失败时的错误对象
    public DbError error() {
        return error;
    }
}

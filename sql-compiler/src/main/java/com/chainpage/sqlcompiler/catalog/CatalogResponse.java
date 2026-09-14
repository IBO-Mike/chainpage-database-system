package com.chainpage.sqlcompiler.catalog;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;

public final class CatalogResponse {
    private final Map<String, Object> data;
    private final CatalogError error;

    private CatalogResponse(Map<String, Object> data, CatalogError error) {
        this.data = data == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(data));
        this.error = error;
    }

    public static CatalogResponse success(Map<String, Object> data) {
        return new CatalogResponse(data, null);
    }

    public static CatalogResponse failure(CatalogError error) {
        return new CatalogResponse(null, error);
    }

    public boolean ok() { return error == null; }
    public Map<String, Object> data() { return data; }
    public CatalogError error() { return error; }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", ok());
        if (ok()) result.put("data", data);
        else result.put("error", error.toMap());
        return result;
    }
}

package org.chainpage.storage;

import java.util.LinkedHashMap;
import java.util.Map;

public final class StorageException extends RuntimeException {
    private final String code;
    private final Integer pageId;
    public StorageException(String code, String message) { this(code, message, null, null); }
    public StorageException(String code, String message, Integer pageId) { this(code, message, pageId, null); }
    public StorageException(String code, String message, Integer pageId, Throwable cause) {
        super(message, cause); this.code = code; this.pageId = pageId;
    }
    public String code() { return code; }
    public Map<String,Object> error(String requestId) {
        Map<String,Object> e = new LinkedHashMap<>();
        e.put("requestId", requestId); e.put("statementIndex", null); e.put("stage", "PAGE");
        e.put("code", code); e.put("message", getMessage()); e.put("line", null); e.put("column", null); e.put("pageId", pageId);
        return e;
    }
}

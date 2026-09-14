package com.chainpage.sqlcompiler.semantic;

import java.util.List;
import java.util.Map;

public record AnalyzeRequest(List<Map<String, Object>> statements,
                             Map<String, Object> catalogSnapshot) {
}

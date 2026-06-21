package com.example.llmchat.dto;

import java.util.List;
import java.util.Map;

public record MemorySnapshotResponse(
        String sessionId,
        MemoryLayerSnapshot shortTerm,
        MemoryLayerSnapshot working,
        MemoryLayerSnapshot longTerm,
        List<String> memoryLogs) {
}

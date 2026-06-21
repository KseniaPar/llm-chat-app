package com.example.llmchat.dto;

import java.util.List;

public record AgentResponse(
        String response,
        String sessionId,
        List<String> logs,
        TokenStats tokens,
        MemoryContextSnapshot memorySnapshot,
        List<String> memoryLogs,
        UserProfileSnapshot profileSnapshot,
        List<String> personalizationLogs,
        TaskStateSnapshot taskStateSnapshot,
        List<String> taskStateLogs) {
}

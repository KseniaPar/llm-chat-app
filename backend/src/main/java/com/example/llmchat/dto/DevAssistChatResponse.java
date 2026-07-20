package com.example.llmchat.dto;

import java.util.List;

public record DevAssistChatResponse(
        String question,
        String answer,
        String model,
        long durationMs,
        String gitBranch,
        String gitCommit,
        List<String> sources,
        List<McpToolCallLogDto> mcpToolCalls) {
}

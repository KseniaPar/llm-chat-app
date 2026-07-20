package com.example.llmchat.dto;

import java.util.List;

public record FileAssistChatResponse(
        String goal,
        String answer,
        String model,
        long durationMs,
        boolean dryRun,
        List<String> appliedPaths,
        List<FileWriteResultDto> writes,
        List<McpToolCallLogDto> mcpToolCalls) {
}

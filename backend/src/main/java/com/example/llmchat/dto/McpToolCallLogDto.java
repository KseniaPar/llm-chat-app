package com.example.llmchat.dto;

public record McpToolCallLogDto(
        String serverName,
        String toolName,
        String arguments,
        String resultPreview,
        long durationMs
) {
}

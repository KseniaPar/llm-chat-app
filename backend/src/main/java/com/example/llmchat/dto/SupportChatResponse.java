package com.example.llmchat.dto;

import java.util.List;

public record SupportChatResponse(
        String question,
        String ticketId,
        String answer,
        String model,
        long durationMs,
        List<String> sources,
        List<McpToolCallLogDto> mcpToolCalls) {
}

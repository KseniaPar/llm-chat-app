package com.example.llmchat.dto;

import java.time.Instant;
import java.util.List;

public record McpToolsResponse(
        boolean connected,
        int toolCount,
        List<String> servers,
        List<McpToolDto> tools,
        String sandboxPath,
        String message,
        Instant checkedAt
) {
}

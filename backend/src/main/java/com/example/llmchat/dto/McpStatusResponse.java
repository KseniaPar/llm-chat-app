package com.example.llmchat.dto;

import java.time.Instant;
import java.util.List;

public record McpStatusResponse(
        boolean connected,
        int toolCount,
        List<String> servers,
        String sandboxPath,
        String message,
        Instant checkedAt
) {
}

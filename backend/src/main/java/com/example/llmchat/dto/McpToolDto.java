package com.example.llmchat.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record McpToolDto(
        String serverName,
        String name,
        String description,
        JsonNode inputSchema
) {
}

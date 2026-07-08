package com.example.llmchat.dto;

import java.util.List;

public record LocalLlmStatusResponse(
        boolean online,
        String baseUrl,
        String configuredModel,
        boolean modelAvailable,
        List<String> installedModels,
        String message) {
}

package com.example.llmchat.dto;

import java.util.List;

public record LocalLlmDemoRunResponse(
        String model,
        long totalDurationMs,
        List<LocalLlmChatResponse> results) {
}

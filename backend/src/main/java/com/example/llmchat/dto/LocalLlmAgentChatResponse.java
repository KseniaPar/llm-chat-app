package com.example.llmchat.dto;

import java.util.List;

public record LocalLlmAgentChatResponse(
        String response,
        String sessionId,
        String model,
        long durationMs,
        long evalCount,
        List<String> logs) {
}

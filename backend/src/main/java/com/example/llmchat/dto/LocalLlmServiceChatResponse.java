package com.example.llmchat.dto;

public record LocalLlmServiceChatResponse(
        String prompt,
        String answer,
        String model,
        long durationMs,
        long evalCount,
        int promptChars,
        int rateLimitRemaining) {
}

package com.example.llmchat.dto;

public record LocalLlmChatResponse(
        String prompt,
        String answer,
        String model,
        long durationMs,
        long evalCount) {
}

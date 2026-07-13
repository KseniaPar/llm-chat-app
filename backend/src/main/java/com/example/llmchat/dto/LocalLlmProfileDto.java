package com.example.llmchat.dto;

public record LocalLlmProfileDto(
        String label,
        String model,
        double temperature,
        int maxTokens,
        int contextWindow,
        String systemPrompt,
        String quantizationNote,
        boolean modelAvailable) {
}

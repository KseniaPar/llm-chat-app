package com.example.llmchat.localllm;

public record LocalLlmProfile(
        String label,
        String model,
        double temperature,
        int maxTokens,
        int contextWindow,
        String systemPrompt,
        String quantizationNote) {
}

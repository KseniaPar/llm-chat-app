package com.example.llmchat.dto;

public record LocalLlmServiceInfoResponse(
        boolean online,
        boolean modelAvailable,
        String model,
        String ollamaBaseUrl,
        String chatEndpoint,
        String statusEndpoint,
        String verifyEndpoint,
        int rateLimitPerMinute,
        int maxPromptChars,
        int maxConcurrentRequests,
        int contextWindow,
        boolean apiKeyRequired,
        String message) {
}

package com.example.llmchat.dto;

public record ModelMetrics(
        long responseTimeMs,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        double costUsd,
        String modelId) {
}

package com.example.llmchat.dto;

public record ModelAnalysisRequest(
        String prompt,
        String weak,
        String medium,
        String strong,
        ModelMetrics weakMetrics,
        ModelMetrics mediumMetrics,
        ModelMetrics strongMetrics) {
}

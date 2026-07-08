package com.example.llmchat.dto;

import java.util.List;

public record RagLlmEvalResponse(
        int questionCount,
        RagLlmEvalSummaryDto localSummary,
        RagLlmEvalSummaryDto cloudSummary,
        List<RagLlmEvalResultDto> results) {

    public record RagLlmEvalSummaryDto(
            RagLlmProviderInfoDto provider,
            long avgGenerationMs,
            long maxGenerationMs,
            int successCount,
            int errorCount,
            int totalSourceMatches,
            double avgSourceMatches,
            String qualityAssessment,
            String stabilityAssessment) {
    }

    public record RagLlmProviderInfoDto(
            String provider,
            String model) {
    }
}

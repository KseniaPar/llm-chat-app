package com.example.llmchat.dto;

public record LocalLlmOptimizationCompareResponse(
        String question,
        RagQueryResponse baselineResponse,
        RagQueryResponse optimizedResponse,
        LocalLlmOptimizationSummaryDto summary) {
}

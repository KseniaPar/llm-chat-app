package com.example.llmchat.dto;

public record LocalLlmOptimizationSummaryDto(
        long baselineGenerationMs,
        long optimizedGenerationMs,
        long retrievalMs,
        String speedWinner,
        long baselineTokens,
        long optimizedTokens,
        String resourceNote,
        int baselineSourceMatches,
        int optimizedSourceMatches,
        String qualityNote,
        boolean baselineSuccess,
        boolean optimizedSuccess) {
}

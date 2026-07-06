package com.example.llmchat.dto;

public record RagModeCompareResponse(
        String question,
        String rewrittenQuery,
        double minSimilarity,
        int searchPoolSize,
        RagModeResultDto raw,
        RagModeResultDto filtered,
        RagModeResultDto rewriteFiltered) {
}

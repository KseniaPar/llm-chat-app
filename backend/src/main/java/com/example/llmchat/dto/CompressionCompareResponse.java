package com.example.llmchat.dto;

public record CompressionCompareResponse(
        CompressionVariantResult raw,
        CompressionVariantResult compressed,
        int historyTokensSaved,
        int sessionTokensSaved,
        double sessionCostSavedUsd,
        double historySavingsPercent,
        double sessionSavingsPercent,
        int modelContextLimit,
        int probeTurn) {
}

package com.example.llmchat.dto;

import java.util.List;

public record CompressionVariantResult(
        String mode,
        String title,
        List<TokenDemoStep> steps,
        String probeAnswer,
        int finalHistoryTokens,
        int sessionTotalTokens,
        double sessionCostUsd,
        List<CompressionEvent> compressionEvents,
        boolean failed,
        String liveApiError,
        Integer liveApiStatusCode) {
}

package com.example.llmchat.dto;

import java.util.List;
import java.util.Map;

public record StrategyVariantResult(
        String mode,
        String title,
        List<TokenDemoStep> steps,
        String probeAnswer,
        String finalAnswer,
        int finalHistoryTokens,
        int sessionTotalTokens,
        double sessionCostUsd,
        int factsCount,
        int messagesInContext,
        List<Map<String, String>> factsSnapshots,
        boolean failed,
        String liveApiError,
        Integer liveApiStatusCode) {
}

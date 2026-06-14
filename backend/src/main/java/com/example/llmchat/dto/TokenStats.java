package com.example.llmchat.dto;

public record TokenStats(
        int currentPromptTokens,
        int historyTokens,
        int requestTokensEstimate,
        int promptTokensActual,
        int responseTokens,
        int totalTokensActual,
        int sessionPromptTokens,
        int sessionCompletionTokens,
        int sessionTotalTokens,
        double requestCostUsd,
        double sessionCostUsd,
        int modelContextLimit,
        int contextRemaining,
        boolean nearContextLimit,
        boolean contextOverflow) {
}

package com.example.llmchat.dto;

import java.util.List;

public record TokenDemoStep(
        int turn,
        int currentPromptTokens,
        int historyTokens,
        int requestTokens,
        int responseTokens,
        int sessionTotalTokens,
        double sessionCostUsd) {
}

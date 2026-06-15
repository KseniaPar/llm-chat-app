package com.example.llmchat.dto;

public record CompressionEvent(
        int atTurn,
        int messagesSummarized,
        int summaryTokens,
        String summaryPreview) {
}

package com.example.llmchat.dto;

public record RagQuoteDto(
        String chunkId,
        String source,
        String section,
        int rank,
        String text,
        double semanticScore,
        double relevanceScore) {
}

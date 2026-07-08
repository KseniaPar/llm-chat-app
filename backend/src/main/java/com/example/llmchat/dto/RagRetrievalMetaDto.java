package com.example.llmchat.dto;

import com.example.llmchat.rag.RagRetrievalMode;

import java.util.List;

public record RagRetrievalMetaDto(
        RagRetrievalMode retrievalMode,
        String originalQuery,
        String rewrittenQuery,
        String searchQuery,
        int topKBefore,
        int topKAfter,
        int droppedCount,
        double minSimilarity,
        List<Double> scoresBefore,
        List<Double> scoresAfter,
        String embeddingSource) {
}

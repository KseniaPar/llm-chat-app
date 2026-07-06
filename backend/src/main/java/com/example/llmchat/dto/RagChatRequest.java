package com.example.llmchat.dto;

import com.example.llmchat.rag.ChunkingStrategy;
import com.example.llmchat.rag.RagRetrievalMode;

public record RagChatRequest(
        String sessionId,
        String message,
        ChunkingStrategy strategy,
        Integer topK,
        RagRetrievalMode mode,
        Double minSimilarity) {
}

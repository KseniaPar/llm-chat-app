package com.example.llmchat.dto;

import com.example.llmchat.rag.ChunkingStrategy;
import com.example.llmchat.rag.RagLlmProvider;
import com.example.llmchat.rag.RagRetrievalMode;

public record RagQueryRequest(
        String question,
        Boolean useRag,
        ChunkingStrategy strategy,
        Integer topK,
        RagRetrievalMode mode,
        Double minSimilarity,
        RagLlmProvider llmProvider) {
}

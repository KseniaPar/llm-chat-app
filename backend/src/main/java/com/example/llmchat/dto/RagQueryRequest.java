package com.example.llmchat.dto;

import com.example.llmchat.rag.ChunkingStrategy;

public record RagQueryRequest(
        String question,
        Boolean useRag,
        ChunkingStrategy strategy,
        Integer topK) {
}

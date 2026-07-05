package com.example.llmchat.rag;

public record RagChunk(
        String chunkId,
        String source,
        String title,
        String section,
        String content,
        int charStart,
        int charEnd,
        int tokenCount) {
}

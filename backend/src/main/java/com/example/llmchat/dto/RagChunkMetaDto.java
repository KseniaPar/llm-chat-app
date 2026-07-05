package com.example.llmchat.dto;

public record RagChunkMetaDto(
        String position,
        int chunkIndex,
        int totalChunks,
        String chunkId,
        String source,
        String title,
        String section,
        int charStart,
        int charEnd,
        int tokenCount,
        String preview) {
}

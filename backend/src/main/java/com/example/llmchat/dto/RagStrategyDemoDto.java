package com.example.llmchat.dto;

import com.example.llmchat.rag.ChunkingStrategy;

import java.util.List;

public record RagStrategyDemoDto(
        ChunkingStrategy strategy,
        String strategyLabel,
        String strategyDescription,
        int chunkCount,
        double avgChunkSize,
        int minChunkSize,
        int maxChunkSize,
        String indexedAt,
        int embeddingDimensions,
        List<RagChunkMetaDto> sampleChunks) {
}

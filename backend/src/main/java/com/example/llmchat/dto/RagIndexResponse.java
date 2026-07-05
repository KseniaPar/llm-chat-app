package com.example.llmchat.dto;

import com.example.llmchat.rag.ChunkingStrategy;
import com.example.llmchat.rag.RagIndexingService;

import java.util.List;

public record RagIndexResponse(
        ChunkingStrategy strategy,
        int chunkCount,
        double avgChunkSize,
        int minChunkSize,
        int maxChunkSize,
        List<RagIndexingService.ChunkSample> samples) {

    public static RagIndexResponse from(RagIndexingService.IndexResult result) {
        return new RagIndexResponse(
                result.strategy(),
                result.chunkCount(),
                result.avgChunkSize(),
                result.minChunkSize(),
                result.maxChunkSize(),
                result.samples());
    }
}

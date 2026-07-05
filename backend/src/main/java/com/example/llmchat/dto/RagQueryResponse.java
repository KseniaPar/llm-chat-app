package com.example.llmchat.dto;

import java.util.List;

public record RagQueryResponse(
        String answer,
        List<ChunkUsedDto> chunksUsed,
        String mode) {

    public record ChunkUsedDto(
            String chunkId,
            String source,
            String section,
            double score,
            String preview) {
    }
}

package com.example.llmchat.dto;

import java.util.List;

public record RagQueryResponse(
        String answer,
        List<ChunkUsedDto> chunksUsed,
        String mode,
        RagRetrievalMetaDto retrievalMeta) {

    public RagQueryResponse(String answer, List<ChunkUsedDto> chunksUsed, String mode) {
        this(answer, chunksUsed, mode, null);
    }

    public record ChunkUsedDto(
            String chunkId,
            String source,
            String section,
            double score,
            String preview) {
    }
}

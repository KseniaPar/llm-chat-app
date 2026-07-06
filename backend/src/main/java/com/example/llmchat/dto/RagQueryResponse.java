package com.example.llmchat.dto;

import java.util.List;

public record RagQueryResponse(
        String answer,
        List<ChunkUsedDto> chunksUsed,
        String mode,
        RagRetrievalMetaDto retrievalMeta,
        List<RagSourceDto> sources,
        List<RagQuoteDto> quotes,
        String confidence) {

    public RagQueryResponse(String answer, List<ChunkUsedDto> chunksUsed, String mode) {
        this(answer, chunksUsed, mode, null, List.of(), List.of(), null);
    }

    public RagQueryResponse(String answer, List<ChunkUsedDto> chunksUsed, String mode, RagRetrievalMetaDto retrievalMeta) {
        this(answer, chunksUsed, mode, retrievalMeta, List.of(), List.of(), null);
    }

    public record ChunkUsedDto(
            String chunkId,
            String source,
            String section,
            double score,
            String preview) {
    }
}

package com.example.llmchat.dto;

import com.example.llmchat.rag.RagLlmProvider;

import java.util.List;

public record RagQueryResponse(
        String answer,
        List<ChunkUsedDto> chunksUsed,
        String mode,
        RagRetrievalMetaDto retrievalMeta,
        List<RagSourceDto> sources,
        List<RagQuoteDto> quotes,
        String confidence,
        RagLlmProvider llmProvider,
        String llmModel,
        Long retrievalDurationMs,
        Long generationDurationMs,
        Long tokenCount,
        Boolean generationSuccess,
        String generationError) {

    public RagQueryResponse(String answer, List<ChunkUsedDto> chunksUsed, String mode) {
        this(answer, chunksUsed, mode, null, List.of(), List.of(), null,
                null, null, null, null, null, null, null);
    }

    public RagQueryResponse(String answer, List<ChunkUsedDto> chunksUsed, String mode, RagRetrievalMetaDto retrievalMeta) {
        this(answer, chunksUsed, mode, retrievalMeta, List.of(), List.of(), null,
                null, null, null, null, null, null, null);
    }

    public RagQueryResponse(
            String answer,
            List<ChunkUsedDto> chunksUsed,
            String mode,
            RagRetrievalMetaDto retrievalMeta,
            List<RagSourceDto> sources,
            List<RagQuoteDto> quotes,
            String confidence) {
        this(answer, chunksUsed, mode, retrievalMeta, sources, quotes, confidence,
                null, null, null, null, null, null, null);
    }

    public record ChunkUsedDto(
            String chunkId,
            String source,
            String section,
            double score,
            String preview) {
    }
}

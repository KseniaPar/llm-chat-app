package com.example.llmchat.dto;

import com.example.llmchat.rag.RagRetrievalMode;

public record RagModeResultDto(
        RagRetrievalMode mode,
        RagQueryResponse response,
        RagRetrievalMetaDto retrieval) {
}

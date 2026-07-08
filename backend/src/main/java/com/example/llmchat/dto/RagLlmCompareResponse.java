package com.example.llmchat.dto;

import com.example.llmchat.rag.RagLlmProvider;

public record RagLlmCompareResponse(
        String question,
        RagQueryResponse localResponse,
        RagQueryResponse cloudResponse,
        RagLlmCompareSummaryDto summary) {

    public record RagLlmCompareSummaryDto(
            long localGenerationMs,
            long cloudGenerationMs,
            long retrievalMs,
            String speedWinner,
            int localSourceMatches,
            int cloudSourceMatches,
            boolean localSuccess,
            boolean cloudSuccess,
            String qualityNote,
            String stabilityNote) {
    }
}

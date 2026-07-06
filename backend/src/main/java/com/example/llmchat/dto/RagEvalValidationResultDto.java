package com.example.llmchat.dto;

public record RagEvalValidationResultDto(
        RagEvalQuestionDto question,
        RagQueryResponse response,
        boolean hasSources,
        boolean hasQuotes,
        boolean quotesValid,
        boolean meaningAligned,
        boolean passed) {
}

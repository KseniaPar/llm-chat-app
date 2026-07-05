package com.example.llmchat.dto;

public record RagQueryCompareResponse(
        String question,
        RagQueryResponse withoutRag,
        RagQueryResponse withRag) {
}

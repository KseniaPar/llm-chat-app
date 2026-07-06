package com.example.llmchat.dto;

public record RagSourceDto(
        String source,
        String section,
        String chunkId) {
}

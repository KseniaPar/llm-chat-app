package com.example.llmchat.dto;

import java.time.Instant;
import java.util.List;

public record RagChatMessageDto(
        String role,
        String content,
        List<RagSourceDto> sources,
        List<RagQuoteDto> quotes,
        String confidence,
        Instant createdAt) {

    public static RagChatMessageDto user(String content) {
        return new RagChatMessageDto("user", content, List.of(), List.of(), null, Instant.now());
    }

    public static RagChatMessageDto assistant(
            String content,
            List<RagSourceDto> sources,
            List<RagQuoteDto> quotes,
            String confidence) {
        return new RagChatMessageDto("assistant", content, sources, quotes, confidence, Instant.now());
    }
}

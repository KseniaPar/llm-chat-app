package com.example.llmchat.dto;

public record LocalLlmServiceVerifyCheckDto(
        String id,
        String name,
        boolean passed,
        long durationMs,
        String detail) {
}

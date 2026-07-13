package com.example.llmchat.dto;

public record LocalLlmOptimizationLastRunDto(
        long completedAtMs,
        LocalLlmOptimizationRunResponse response) {
}

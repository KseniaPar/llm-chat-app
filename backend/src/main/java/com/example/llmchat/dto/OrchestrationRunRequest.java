package com.example.llmchat.dto;

public record OrchestrationRunRequest(
        String scenarioId,
        String query,
        String filename,
        String topic) {
}

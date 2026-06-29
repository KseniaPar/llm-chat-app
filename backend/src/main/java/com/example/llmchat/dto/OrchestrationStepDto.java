package com.example.llmchat.dto;

public record OrchestrationStepDto(
        String serverName,
        String toolName,
        String args,
        String result,
        long durationMs,
        String status) {
}

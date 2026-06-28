package com.example.llmchat.dto;

import java.util.List;

public record PipelineStepDto(
        int step,
        String toolName,
        String serverName,
        String arguments,
        String resultPreview,
        long durationMs) {
}

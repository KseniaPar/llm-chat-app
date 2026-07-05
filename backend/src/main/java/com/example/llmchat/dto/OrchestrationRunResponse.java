package com.example.llmchat.dto;

import java.util.List;

public record OrchestrationRunResponse(
        List<OrchestrationStepDto> steps,
        String assistantMessage,
        List<McpToolCallLogDto> mcpToolCalls,
        long totalDurationMs,
        String scenarioId) {
}

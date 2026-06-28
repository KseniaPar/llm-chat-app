package com.example.llmchat.dto;

import java.util.List;

public record PipelineRunResponse(
        String query,
        String filename,
        String filePath,
        long totalDurationMs,
        List<PipelineStepDto> steps,
        List<McpToolCallLogDto> mcpToolCalls,
        String assistantMessage) {
}

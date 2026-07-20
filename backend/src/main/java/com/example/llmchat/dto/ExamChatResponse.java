package com.example.llmchat.dto;

import java.util.List;

public record ExamChatResponse(
        String question,
        String answer,
        String model,
        long durationMs,
        boolean trustCited,
        List<String> sources,
        List<ExamCitationDto> citations,
        List<McpToolCallLogDto> toolCalls) {
}

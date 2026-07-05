package com.example.llmchat.dto;

import java.util.List;

public record RagEvalResponse(
        int totalQuestions,
        int ragWithSources,
        int ragWithoutSources,
        List<RagEvalResultDto> results) {
}

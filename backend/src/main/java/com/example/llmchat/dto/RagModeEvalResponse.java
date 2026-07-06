package com.example.llmchat.dto;

import java.util.List;

public record RagModeEvalResponse(
        int totalQuestions,
        int rawWithSources,
        int filteredWithSources,
        int rewriteFilteredWithSources,
        List<RagModeEvalResultDto> results) {
}

package com.example.llmchat.dto;

import java.util.List;

public record RagEvalValidationResponse(
        int totalQuestions,
        int passed,
        int failed,
        List<RagEvalValidationResultDto> results) {
}

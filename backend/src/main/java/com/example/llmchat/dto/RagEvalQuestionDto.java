package com.example.llmchat.dto;

import java.util.List;

public record RagEvalQuestionDto(
        String id,
        String section,
        String question,
        String expectedAnswer,
        List<String> expectedSources) {
}

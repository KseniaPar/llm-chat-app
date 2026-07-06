package com.example.llmchat.dto;

import java.util.List;

public record RagDialogMemoryDto(
        String dialogGoal,
        List<String> clarifications,
        List<String> fixedTerms) {

    public static RagDialogMemoryDto empty() {
        return new RagDialogMemoryDto(null, List.of(), List.of());
    }
}

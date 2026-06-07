package com.example.llmchat.dto;

public record ReasoningCompareResponse(
        String direct,
        String stepByStep,
        String metaPrompt,
        String metaPromptAnswer,
        String experts,
        String comparison,
        String logs) {
}

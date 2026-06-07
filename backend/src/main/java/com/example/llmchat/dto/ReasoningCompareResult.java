package com.example.llmchat.dto;

public record ReasoningCompareResult(
        String direct,
        String stepByStep,
        String metaPrompt,
        String metaPromptAnswer,
        String experts,
        String comparison,
        String logs) {
}

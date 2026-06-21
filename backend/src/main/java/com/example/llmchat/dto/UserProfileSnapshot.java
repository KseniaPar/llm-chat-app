package com.example.llmchat.dto;

public record UserProfileSnapshot(
        String displayName,
        String responseStyle,
        String responseFormat,
        String constraints,
        boolean appliedToPrompt) {
}

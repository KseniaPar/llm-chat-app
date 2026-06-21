package com.example.llmchat.dto;

public record TaskStateSnapshot(
        String phase,
        String phaseLabel,
        String currentStep,
        String expectedAction,
        String taskTitle,
        boolean paused,
        boolean appliedToPrompt,
        boolean active) {
}

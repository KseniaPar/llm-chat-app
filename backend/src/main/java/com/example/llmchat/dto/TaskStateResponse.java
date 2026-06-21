package com.example.llmchat.dto;

public record TaskStateResponse(
        String phase,
        String phaseLabel,
        String currentStep,
        String expectedAction,
        String taskTitle,
        boolean paused,
        boolean active) {
}

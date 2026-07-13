package com.example.llmchat.dto;

public record LocalLlmOptimizationRunStatusDto(
        boolean running,
        int currentStep,
        int totalSteps,
        String currentScenarioTitle,
        long startedAtMs,
        String lastError,
        boolean hasLastRun,
        Long lastRunCompletedAtMs) {

    public static LocalLlmOptimizationRunStatusDto idle(boolean hasLastRun, Long lastRunCompletedAtMs) {
        return new LocalLlmOptimizationRunStatusDto(
                false, 0, 0, null, 0, null, hasLastRun, lastRunCompletedAtMs);
    }
}

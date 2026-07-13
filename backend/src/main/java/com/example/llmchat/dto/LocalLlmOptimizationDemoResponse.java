package com.example.llmchat.dto;

import java.util.List;

public record LocalLlmOptimizationDemoResponse(
        String dayLabel,
        String description,
        String useCase,
        LocalLlmProfileDto baselineProfile,
        LocalLlmProfileDto optimizedProfile,
        List<String> optimizationSteps,
        List<String> comparisonAxes,
        List<RagLocalDemoScenarioDto> scenarios) {
}

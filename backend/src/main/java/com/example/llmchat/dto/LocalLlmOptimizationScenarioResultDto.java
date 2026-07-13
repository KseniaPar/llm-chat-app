package com.example.llmchat.dto;

public record LocalLlmOptimizationScenarioResultDto(
        RagLocalDemoScenarioDto scenario,
        LocalLlmOptimizationCompareResponse compare) {
}

package com.example.llmchat.dto;

import java.util.List;

public record LocalLlmDemoResponse(
        String dayLabel,
        String description,
        String baseUrl,
        String model,
        double temperature,
        int maxTokens,
        List<String> pipelineSteps,
        List<LocalLlmDemoScenarioDto> scenarios) {
}

package com.example.llmchat.dto;

public record RagLocalDemoScenarioResultDto(
        RagLocalDemoScenarioDto scenario,
        RagLlmCompareResponse compare) {
}

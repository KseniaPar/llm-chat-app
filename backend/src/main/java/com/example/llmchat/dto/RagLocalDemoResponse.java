package com.example.llmchat.dto;

import java.util.List;

public record RagLocalDemoResponse(
        String title,
        String description,
        String localIndexDbPath,
        int localIndexChunkCount,
        String cloudIndexDbPath,
        int cloudIndexChunkCount,
        String localChatModel,
        String localEmbeddingModel,
        String cloudChatModel,
        String cloudEmbeddingModel,
        String localLlmStatus,
        boolean localIndexReady,
        List<String> pipelineSteps,
        List<String> comparisonCriteria,
        List<RagLocalDemoScenarioDto> scenarios) {
}

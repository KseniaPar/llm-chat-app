package com.example.llmchat.dto;

import java.util.List;

public record RagLocalDemoRunResponse(
        int scenarioCount,
        long totalDurationMs,
        RagLocalDemoRunSummaryDto summary,
        List<RagLocalDemoScenarioResultDto> results) {

    public record RagLocalDemoRunSummaryDto(
            long avgLocalGenerationMs,
            long avgCloudGenerationMs,
            long avgRetrievalMs,
            int localSpeedWins,
            int cloudSpeedWins,
            int localSuccessCount,
            int cloudSuccessCount,
            String speedVerdict,
            String qualityVerdict,
            String stabilityVerdict) {
    }
}

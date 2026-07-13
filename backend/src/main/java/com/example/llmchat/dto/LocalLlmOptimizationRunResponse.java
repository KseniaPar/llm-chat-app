package com.example.llmchat.dto;

import java.util.List;

public record LocalLlmOptimizationRunResponse(
        int scenarioCount,
        long totalDurationMs,
        LocalLlmOptimizationRunSummaryDto summary,
        List<LocalLlmOptimizationScenarioResultDto> results) {

    public record LocalLlmOptimizationRunSummaryDto(
            long avgBaselineMs,
            long avgOptimizedMs,
            long avgRetrievalMs,
            int baselineWins,
            int optimizedWins,
            int baselineSuccess,
            int optimizedSuccess,
            long baselineTokensTotal,
            long optimizedTokensTotal,
            String speedVerdict,
            String qualityVerdict,
            String resourceVerdict) {
    }
}

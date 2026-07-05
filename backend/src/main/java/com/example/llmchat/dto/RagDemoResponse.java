package com.example.llmchat.dto;

import java.util.List;

public record RagDemoResponse(
        String dayLabel,
        String corpusFile,
        int corpusDocuments,
        int corpusEstimatedPages,
        int corpusTotalChars,
        String indexDbPath,
        List<String> pipelineSteps,
        List<String> technologies,
        RagStrategyDemoDto fixedSize,
        RagStrategyDemoDto structure) {
}

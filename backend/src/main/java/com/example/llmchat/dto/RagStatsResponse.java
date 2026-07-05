package com.example.llmchat.dto;

import com.example.llmchat.rag.RagIndexRepository;

import java.util.List;

public record RagStatsResponse(
        int corpusDocuments,
        int corpusEstimatedPages,
        List<RagIndexRepository.StrategyStats> strategies) {
}

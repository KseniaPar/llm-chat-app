package com.example.llmchat.dto;

public record ModelCompareResult(
        ModelTierResult weak,
        ModelTierResult medium,
        ModelTierResult strong,
        String comparison,
        String logs) {
}

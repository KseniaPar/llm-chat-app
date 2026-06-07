package com.example.llmchat.dto;

public record ModelCompareResponse(
        ModelTierResult weak,
        ModelTierResult medium,
        ModelTierResult strong,
        String comparison,
        String logs) {
}

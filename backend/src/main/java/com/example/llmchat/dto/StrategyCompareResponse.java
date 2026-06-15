package com.example.llmchat.dto;

import java.util.List;

public record StrategyCompareResponse(
        List<StrategyVariantResult> variants,
        int modelContextLimit,
        int probeTurn,
        int dialogTurns) {
}

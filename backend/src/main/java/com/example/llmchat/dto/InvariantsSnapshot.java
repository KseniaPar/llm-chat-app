package com.example.llmchat.dto;

import java.util.List;

public record InvariantsSnapshot(
        int activeCount,
        List<InvariantDto> rules,
        boolean appliedToPrompt,
        List<String> violatedInvariantIds) {
}

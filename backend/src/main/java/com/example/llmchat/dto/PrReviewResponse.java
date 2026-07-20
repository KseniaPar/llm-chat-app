package com.example.llmchat.dto;

import java.util.List;

public record PrReviewResponse(
        String reviewMarkdown,
        String model,
        long durationMs,
        int diffChars,
        int changedFileCount,
        List<String> contextSources,
        String bugsSection,
        String architectureSection,
        String recommendationsSection) {
}

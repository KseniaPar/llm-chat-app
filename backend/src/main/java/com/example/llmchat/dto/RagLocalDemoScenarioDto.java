package com.example.llmchat.dto;

import java.util.List;

public record RagLocalDemoScenarioDto(
        int id,
        String complexity,
        String title,
        String question,
        List<String> expectedSources) {
}

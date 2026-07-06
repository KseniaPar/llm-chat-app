package com.example.llmchat.dto;

import java.util.List;

public record RagScenarioDto(
        String id,
        String title,
        String description,
        List<String> messages) {
}

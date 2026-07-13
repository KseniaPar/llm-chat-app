package com.example.llmchat.dto;

import java.util.List;

public record PlatformModuleDto(
        String id,
        String name,
        String description,
        boolean enabled,
        boolean ready,
        String status,
        List<String> endpoints) {
}

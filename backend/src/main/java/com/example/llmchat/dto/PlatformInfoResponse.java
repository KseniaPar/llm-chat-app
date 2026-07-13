package com.example.llmchat.dto;

import java.util.List;

public record PlatformInfoResponse(
        String name,
        String description,
        String version,
        String profile,
        boolean ready,
        String message,
        List<PlatformModuleDto> modules) {
}

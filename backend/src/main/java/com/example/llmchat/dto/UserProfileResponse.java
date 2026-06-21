package com.example.llmchat.dto;

import java.time.Instant;

public record UserProfileResponse(
        String displayName,
        String responseStyle,
        String responseFormat,
        String constraints,
        Instant updatedAt,
        boolean configured) {
}

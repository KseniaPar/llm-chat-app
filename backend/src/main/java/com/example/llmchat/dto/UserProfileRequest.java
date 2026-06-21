package com.example.llmchat.dto;

public record UserProfileRequest(
        String displayName,
        String responseStyle,
        String responseFormat,
        String constraints) {
}

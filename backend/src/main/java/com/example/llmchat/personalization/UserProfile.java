package com.example.llmchat.personalization;

import java.time.Instant;

public record UserProfile(
        String userId,
        String displayName,
        String responseStyle,
        String responseFormat,
        String constraints,
        Instant updatedAt) {

    public static UserProfile empty(String userId) {
        return new UserProfile(userId, null, null, null, null, null);
    }
}

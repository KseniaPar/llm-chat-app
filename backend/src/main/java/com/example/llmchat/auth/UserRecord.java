package com.example.llmchat.auth;

import java.time.Instant;

public record UserRecord(String id, String username, String passwordHash, Instant createdAt) {
}

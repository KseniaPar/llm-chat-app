package com.example.llmchat.agent;

public enum ContextStrategy {
    SLIDING_WINDOW,
    STICKY_FACTS,
    BRANCHING;

    public static ContextStrategy fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ContextStrategy.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}

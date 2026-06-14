package com.example.llmchat.agent;

public final class LogText {

    private static final int DEFAULT_MAX_LENGTH = 200;

    private LogText() {
    }

    public static String truncate(String text) {
        return truncate(text, DEFAULT_MAX_LENGTH);
    }

    public static String truncate(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "... [+" + (text.length() - maxLength) + " символов]";
    }
}

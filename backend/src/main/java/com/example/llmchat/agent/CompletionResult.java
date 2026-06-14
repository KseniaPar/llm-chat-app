package com.example.llmchat.agent;

public record CompletionResult(
        String content,
        int promptTokens,
        int completionTokens,
        int totalTokens) {

    public static CompletionResult emptyUsage(String content) {
        return new CompletionResult(content, 0, 0, 0);
    }
}

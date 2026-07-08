package com.example.llmchat.rag;

public record RagLlmCompletionResult(
        String content,
        RagLlmProvider provider,
        String model,
        long durationMs,
        long tokenCount,
        boolean success,
        String errorMessage) {

    public static RagLlmCompletionResult success(
            String content,
            RagLlmProvider provider,
            String model,
            long durationMs,
            long tokenCount) {
        return new RagLlmCompletionResult(content, provider, model, durationMs, tokenCount, true, null);
    }

    public static RagLlmCompletionResult failure(RagLlmProvider provider, String model, long durationMs, String error) {
        return new RagLlmCompletionResult(null, provider, model, durationMs, 0, false, error);
    }
}

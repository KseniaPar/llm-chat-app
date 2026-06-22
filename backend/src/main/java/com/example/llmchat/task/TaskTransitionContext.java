package com.example.llmchat.task;

public record TaskTransitionContext(
        String userMessage,
        int priorMessageCount,
        boolean llmProposal,
        boolean autoTrigger) {

    public static TaskTransitionContext empty() {
        return new TaskTransitionContext(null, 0, false, false);
    }

    public static TaskTransitionContext forRule(String userMessage, int priorMessageCount) {
        return new TaskTransitionContext(userMessage, priorMessageCount, false, false);
    }

    public static TaskTransitionContext forLlm(String userMessage) {
        return new TaskTransitionContext(userMessage, 0, true, false);
    }

    public static TaskTransitionContext forAuto() {
        return new TaskTransitionContext(null, 0, false, true);
    }

    public static TaskTransitionContext forUserApi() {
        return new TaskTransitionContext(null, 0, false, false);
    }
}

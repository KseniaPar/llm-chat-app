package com.example.llmchat.task;

public record TaskTransitionRequest(
        TaskTransitionType type,
        TaskTransitionTriggerSource triggerSource,
        TaskState targetState,
        TaskTransitionContext context,
        boolean persist) {

    public static TaskTransitionRequest of(
            TaskTransitionType type,
            TaskTransitionTriggerSource triggerSource,
            TaskState targetState) {
        return new TaskTransitionRequest(type, triggerSource, targetState, TaskTransitionContext.empty(), true);
    }

    public static TaskTransitionRequest withContext(
            TaskTransitionType type,
            TaskTransitionTriggerSource triggerSource,
            TaskState targetState,
            TaskTransitionContext context) {
        return new TaskTransitionRequest(type, triggerSource, targetState, context, true);
    }

    public static TaskTransitionRequest deferred(
            TaskTransitionType type,
            TaskTransitionTriggerSource triggerSource,
            TaskState targetState,
            TaskTransitionContext context) {
        return new TaskTransitionRequest(type, triggerSource, targetState, context, false);
    }
}

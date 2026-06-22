package com.example.llmchat.task;

public record TaskTransitionValidation(
        boolean permitted,
        TaskTransitionRejectionCode rejectionCode,
        String rejectionReason) {

    public static TaskTransitionValidation ok() {
        return new TaskTransitionValidation(true, null, null);
    }

    public static TaskTransitionValidation rejected(
            TaskTransitionRejectionCode code,
            String reason) {
        return new TaskTransitionValidation(false, code, reason);
    }
}

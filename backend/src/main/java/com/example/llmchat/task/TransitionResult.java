package com.example.llmchat.task;

public record TransitionResult(
        boolean accepted,
        TaskTransitionType transitionType,
        TaskState previousState,
        TaskState newState,
        TaskTransitionRejectionCode rejectionCode,
        String rejectionReason) {

    public static TransitionResult accepted(
            TaskTransitionType type,
            TaskState previous,
            TaskState updated) {
        return new TransitionResult(true, type, previous, updated, null, null);
    }

    public static TransitionResult rejected(
            TaskTransitionType type,
            TaskState current,
            TaskTransitionRejectionCode code,
            String reason) {
        return new TransitionResult(false, type, current, current, code, reason);
    }

    public String toLogLine() {
        if (accepted) {
            String from = previousState != null ? previousState.phase().id() : "—";
            String to = newState != null ? newState.phase().id() : "—";
            return "TASK → переход: " + from + " → " + to + " (" + transitionType.name() + ")";
        }
        String from = previousState != null ? previousState.phase().id() : "—";
        String code = rejectionCode != null ? rejectionCode.name() : "REJECTED";
        String detail = rejectionReason != null ? ": " + rejectionReason : "";
        return "TASK → отклонён: " + from + " (" + code + detail + ")";
    }
}

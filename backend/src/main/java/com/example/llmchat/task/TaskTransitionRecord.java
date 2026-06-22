package com.example.llmchat.task;

import java.time.Instant;

public record TaskTransitionRecord(
        long id,
        String sessionId,
        TaskTransitionType transitionType,
        TaskPhase fromPhase,
        TaskPhase toPhase,
        String fromStep,
        String toStep,
        TaskTransitionTriggerSource triggerSource,
        boolean accepted,
        TaskTransitionRejectionCode rejectionCode,
        String rejectionReason,
        Instant createdAt) {
}

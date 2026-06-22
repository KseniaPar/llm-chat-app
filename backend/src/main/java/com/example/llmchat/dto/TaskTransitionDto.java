package com.example.llmchat.dto;

import com.example.llmchat.task.TaskTransitionRecord;
import com.example.llmchat.task.TaskTransitionType;

import java.time.Instant;

public record TaskTransitionDto(
        long id,
        String transitionType,
        String transitionLabel,
        String fromPhase,
        String toPhase,
        String fromStep,
        String toStep,
        String triggerSource,
        boolean accepted,
        String rejectionCode,
        String rejectionReason,
        Instant createdAt) {

    public static TaskTransitionDto from(TaskTransitionRecord record) {
        return new TaskTransitionDto(
                record.id(),
                record.transitionType().name(),
                record.transitionType().displayLabel(),
                record.fromPhase() != null ? record.fromPhase().id() : null,
                record.toPhase() != null ? record.toPhase().id() : null,
                record.fromStep(),
                record.toStep(),
                record.triggerSource().name(),
                record.accepted(),
                record.rejectionCode() != null ? record.rejectionCode().name() : null,
                record.rejectionReason(),
                record.createdAt());
    }
}

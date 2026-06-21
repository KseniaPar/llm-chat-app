package com.example.llmchat.task;

import java.time.Instant;

public record TaskState(
        String sessionId,
        TaskPhase phase,
        String currentStep,
        String expectedAction,
        boolean paused,
        String taskTitle,
        Instant updatedAt) {

    public static TaskState initialPlanning(String sessionId, String taskTitle) {
        return new TaskState(
                sessionId,
                TaskPhase.PLANNING,
                PlanningSteps.CLARIFICATION,
                "Задать уточняющие вопросы: зачем готовится, срок, что уже знает, что путает, какой формат занятий",
                false,
                taskTitle,
                Instant.now());
    }
}

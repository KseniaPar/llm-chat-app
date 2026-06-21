package com.example.llmchat.task;

import org.springframework.stereotype.Component;

@Component
public class TaskStateMachine {

    public TaskState applyProposal(TaskState current, TaskStateProposal proposal) {
        if (proposal == null) {
            return current;
        }
        TaskPhase resolvedPhase = resolvePhase(current, proposal);
        String currentStep = firstNonBlank(proposal.currentStep(), current.currentStep());
        String expectedAction = firstNonBlank(proposal.expectedAction(), current.expectedAction());
        String taskTitle = firstNonBlank(proposal.taskTitle(), current.taskTitle());
        return new TaskState(
                current.sessionId(),
                resolvedPhase,
                currentStep,
                expectedAction,
                current.paused(),
                taskTitle,
                current.updatedAt());
    }

    private TaskPhase resolvePhase(TaskState current, TaskStateProposal proposal) {
        if (current.paused()) {
            return current.phase();
        }
        if (proposal.phase() == null) {
            return current.phase();
        }
        if (proposal.phase() == current.phase()) {
            return current.phase();
        }
        if (isForwardTransition(current.phase(), proposal.phase())) {
            return proposal.phase();
        }
        return current.phase();
    }

    private boolean isForwardTransition(TaskPhase from, TaskPhase to) {
        return to.ordinal() == from.ordinal() + 1;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return fallback;
    }

    public record TaskStateProposal(
            TaskPhase phase,
            String currentStep,
            String expectedAction,
            String taskTitle) {
    }
}

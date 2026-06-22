package com.example.llmchat.task;

import org.springframework.stereotype.Component;

@Component
public class TaskStateMachine {

    public TaskState applyProposal(TaskState current, TaskStateProposal proposal) {
        return evaluateProposal(current, proposal).state();
    }

    public static ApplyResult evaluateProposal(TaskState current, TaskStateProposal proposal) {
        if (proposal == null) {
            return ApplyResult.unchanged(current);
        }
        PhaseResolution resolution = resolvePhase(current, proposal);
        String currentStep = firstNonBlank(proposal.currentStep(), current.currentStep());
        String expectedAction = firstNonBlank(proposal.expectedAction(), current.expectedAction());
        String taskTitle = firstNonBlank(proposal.taskTitle(), current.taskTitle());
        TaskState next = new TaskState(
                current.sessionId(),
                resolution.phase(),
                currentStep,
                expectedAction,
                current.paused(),
                taskTitle,
                current.updatedAt());
        return new ApplyResult(
                next,
                resolution.phaseChanged(),
                resolution.phaseChangeAccepted(),
                resolution.proposedPhase(),
                resolution.rejectionCode(),
                resolution.rejectionReason());
    }

    private static PhaseResolution resolvePhase(TaskState current, TaskStateProposal proposal) {
        if (current.paused()) {
            if (proposal.phase() != null && proposal.phase() != current.phase()) {
                return PhaseResolution.blocked(
                        current.phase(),
                        proposal.phase(),
                        TaskTransitionRejectionCode.PAUSED_BLOCKS_PHASE,
                        TaskTransitionRejectionCode.PAUSED_BLOCKS_PHASE.defaultMessage());
            }
            return PhaseResolution.same(current.phase());
        }
        if (proposal.phase() == null) {
            return PhaseResolution.same(current.phase());
        }
        if (proposal.phase() == current.phase()) {
            return PhaseResolution.same(current.phase());
        }
        if (isForwardTransition(current.phase(), proposal.phase())) {
            return PhaseResolution.forward(current.phase(), proposal.phase());
        }
        if (proposal.phase().ordinal() < current.phase().ordinal()) {
            return PhaseResolution.blocked(
                    current.phase(),
                    proposal.phase(),
                    TaskTransitionRejectionCode.ROLLBACK_NOT_ALLOWED,
                    "Возврат с " + current.phase().id() + " на " + proposal.phase().id() + " запрещён.");
        }
        return PhaseResolution.blocked(
                current.phase(),
                proposal.phase(),
                TaskTransitionRejectionCode.SKIP_NOT_ALLOWED,
                "Пропуск этапа: " + current.phase().id() + " → " + proposal.phase().id() + " не допускается.");
    }

    private static boolean isForwardTransition(TaskPhase from, TaskPhase to) {
        return to.ordinal() == from.ordinal() + 1;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return fallback;
    }

    private record PhaseResolution(
            TaskPhase phase,
            boolean phaseChanged,
            boolean phaseChangeAccepted,
            TaskPhase proposedPhase,
            TaskTransitionRejectionCode rejectionCode,
            String rejectionReason) {

        static PhaseResolution same(TaskPhase phase) {
            return new PhaseResolution(phase, false, true, null, null, null);
        }

        static PhaseResolution forward(TaskPhase from, TaskPhase to) {
            return new PhaseResolution(to, true, true, to, null, null);
        }

        static PhaseResolution blocked(
                TaskPhase from,
                TaskPhase proposed,
                TaskTransitionRejectionCode code,
                String reason) {
            return new PhaseResolution(from, false, false, proposed, code, reason);
        }
    }

    public record TaskStateProposal(
            TaskPhase phase,
            String currentStep,
            String expectedAction,
            String taskTitle) {
    }

    public record ApplyResult(
            TaskState state,
            boolean phaseChanged,
            boolean phaseChangeAccepted,
            TaskPhase proposedPhase,
            TaskTransitionRejectionCode rejectionCode,
            String rejectionReason) {

        public static ApplyResult unchanged(TaskState state) {
            return new ApplyResult(state, false, true, null, null, null);
        }
    }
}

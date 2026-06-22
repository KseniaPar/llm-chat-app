package com.example.llmchat.task;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class TaskTransitionService {

    private static final int DEFAULT_HISTORY_LIMIT = 50;

    private final TaskStateRepository taskStateRepository;
    private final TaskTransitionRepository transitionRepository;
    private final TaskTransitionGraph transitionGraph;

    public TaskTransitionService(
            TaskStateRepository taskStateRepository,
            TaskTransitionRepository transitionRepository,
            TaskTransitionGraph transitionGraph) {
        this.taskStateRepository = taskStateRepository;
        this.transitionRepository = transitionRepository;
        this.transitionGraph = transitionGraph;
    }

    public TransitionResult apply(String sessionId, TaskTransitionRequest request) {
        Optional<TaskState> existing = taskStateRepository.findBySessionId(sessionId);
        TaskState current = existing.orElse(null);
        TaskTransitionType type = request.type();
        TaskState target = request.targetState();
        TaskTransitionContext context = request.context() != null
                ? request.context()
                : TaskTransitionContext.empty();

        TaskPhase targetPhase = target != null ? target.phase() : null;
        TaskTransitionValidation validation = transitionGraph.validate(current, type, targetPhase, context);
        if (!validation.permitted()) {
            TransitionResult result = TransitionResult.rejected(
                    type,
                    current,
                    validation.rejectionCode(),
                    validation.rejectionReason());
            audit(sessionId, result, request.triggerSource(), target);
            return result;
        }

        TaskState updated = target != null ? target : current;
        if (updated != null && !sessionId.equals(updated.sessionId())) {
            updated = copyWithSessionId(updated, sessionId);
        }
        TaskState saved = request.persist() ? taskStateRepository.upsert(updated) : updated;
        TransitionResult result = TransitionResult.accepted(type, current, saved);
        audit(sessionId, result, request.triggerSource(), target);
        return result;
    }

    public TransitionResult applyFromProposal(
            String sessionId,
            TaskState current,
            TaskStateMachine.TaskStateProposal proposal,
            TaskTransitionTriggerSource triggerSource,
            String userMessage,
            boolean persist) {
        if (current == null || proposal == null) {
            return TransitionResult.rejected(
                    TaskTransitionType.UPDATE_IN_PHASE,
                    null,
                    TaskTransitionRejectionCode.NO_TASK_STATE,
                    TaskTransitionRejectionCode.NO_TASK_STATE.defaultMessage());
        }

        TaskTransitionContext context = triggerSource == TaskTransitionTriggerSource.LLM
                ? TaskTransitionContext.forLlm(userMessage)
                : TaskTransitionContext.forRule(userMessage, 0);

        TaskStateMachine.ApplyResult machineResult = TaskStateMachine.evaluateProposal(current, proposal);
        if (!machineResult.phaseChangeAccepted() && machineResult.proposedPhase() != null
                && machineResult.proposedPhase() != current.phase()) {
            TaskTransitionType attempted = transitionGraph.resolveAttemptedType(
                    current.phase(),
                    machineResult.proposedPhase());
            TransitionResult result = TransitionResult.rejected(
                    attempted,
                    current,
                    machineResult.rejectionCode(),
                    machineResult.rejectionReason());
            audit(sessionId, result, triggerSource, targetPhaseSnapshot(current, machineResult.proposedPhase(), machineResult.state()));
            return result;
        }

        TaskTransitionType type = machineResult.phaseChanged()
                ? transitionGraph.transitionTypeForPhaseChange(current.phase(), machineResult.state().phase())
                : TaskTransitionType.UPDATE_IN_PHASE;
        if (type == null) {
            type = TaskTransitionType.UPDATE_IN_PHASE;
        }

        TaskTransitionValidation validation = transitionGraph.validate(
                current,
                type,
                machineResult.state().phase(),
                context);
        if (!validation.permitted()) {
            TransitionResult result = TransitionResult.rejected(
                    type,
                    current,
                    validation.rejectionCode(),
                    validation.rejectionReason());
            audit(sessionId, result, triggerSource, machineResult.state());
            return result;
        }

        TaskState saved = persist ? taskStateRepository.upsert(machineResult.state()) : machineResult.state();
        TransitionResult result = TransitionResult.accepted(type, current, saved);
        audit(sessionId, result, triggerSource, machineResult.state());
        return result;
    }

    public TransitionResult applyFromProposal(
            String sessionId,
            TaskState current,
            TaskStateMachine.TaskStateProposal proposal,
            TaskTransitionTriggerSource triggerSource,
            String userMessage) {
        return applyFromProposal(sessionId, current, proposal, triggerSource, userMessage, true);
    }

    public Optional<TransitionResult> auditUserSkipAttempt(String sessionId, String userMessage) {
        Optional<TaskState> existing = taskStateRepository.findBySessionId(sessionId);
        if (existing.isPresent()) {
            Optional<TaskTransitionGraph.RejectedSkipAttempt> skip =
                    transitionGraph.detectUserSkipAttempt(existing.get(), userMessage);
            if (skip.isEmpty()) {
                return Optional.empty();
            }
            TaskTransitionGraph.RejectedSkipAttempt attempt = skip.get();
            TransitionResult result = TransitionResult.rejected(
                    attempt.attemptedType(),
                    existing.get(),
                    attempt.rejectionCode(),
                    attempt.rejectionReason());
            auditRejectedSkip(sessionId, existing.get(), attempt);
            return Optional.of(result);
        }
        if (!transitionGraph.looksLikeSkipToValidation(userMessage)) {
            return Optional.empty();
        }
        TaskTransitionGraph.RejectedSkipAttempt attempt = new TaskTransitionGraph.RejectedSkipAttempt(
                TaskTransitionType.EXECUTION_TO_VALIDATION,
                TaskPhase.PLANNING,
                TaskPhase.VALIDATION,
                TaskTransitionRejectionCode.SKIP_NOT_ALLOWED,
                "Нельзя перейти к самопроверке до утверждённого плана и разбора тем.");
        TransitionResult result = TransitionResult.rejected(
                attempt.attemptedType(),
                null,
                attempt.rejectionCode(),
                attempt.rejectionReason());
        transitionRepository.insert(
                sessionId,
                attempt.attemptedType(),
                attempt.fromPhase(),
                attempt.toPhase(),
                null,
                null,
                TaskTransitionTriggerSource.RULE,
                false,
                attempt.rejectionCode(),
                attempt.rejectionReason());
        return Optional.of(result);
    }

    public List<TaskTransitionType> allowedNext(String sessionId) {
        return transitionGraph.allowedNext(taskStateRepository.findBySessionId(sessionId).orElse(null));
    }

    public List<TaskTransitionRecord> history(String sessionId, int limit) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        return transitionRepository.listBySessionId(sessionId, limit > 0 ? limit : DEFAULT_HISTORY_LIMIT);
    }

    public List<String> buildTransitionLogs(List<TransitionResult> results) {
        List<String> logs = new ArrayList<>();
        if (results == null) {
            return logs;
        }
        for (TransitionResult result : results) {
            logs.add(result.toLogLine());
        }
        return logs;
    }

    public TaskState buildPlanningAgreementState(TaskState state) {
        return new TaskState(
                state.sessionId(),
                TaskPhase.PLANNING,
                PlanningSteps.AGREEMENT,
                "Кратко предложить план из 3–5 пунктов и спросить: «Начнём по этому плану?»",
                state.paused(),
                state.taskTitle(),
                state.updatedAt());
    }

    public TaskState buildExecutionState(TaskState state) {
        return new TaskState(
                state.sessionId(),
                TaskPhase.EXECUTION,
                "Разбор тем: первая тема по плану",
                "Объяснить первую тему из согласованного плана",
                state.paused(),
                state.taskTitle(),
                state.updatedAt());
    }

    public TaskState buildValidationState(TaskState state) {
        return new TaskState(
                state.sessionId(),
                TaskPhase.VALIDATION,
                "Самопроверка: вопрос 1 из 3",
                "Задать первый вопрос с вариантами A, B, C, D по пройденному материалу",
                state.paused(),
                state.taskTitle(),
                state.updatedAt());
    }

    public TaskState buildDoneState(TaskState state) {
        return new TaskState(
                state.sessionId(),
                TaskPhase.DONE,
                "Тема пройдена",
                "Кратко подвести итог и предложить следующую тему",
                state.paused(),
                state.taskTitle(),
                state.updatedAt());
    }

    public TaskState buildPausedState(TaskState state) {
        return new TaskState(
                state.sessionId(),
                state.phase(),
                state.currentStep(),
                "Ожидание возобновления студентом",
                true,
                state.taskTitle(),
                state.updatedAt());
    }

    public TaskState buildResumedState(TaskState state) {
        return new TaskState(
                state.sessionId(),
                state.phase(),
                state.currentStep(),
                restoreExpectedAction(state),
                false,
                state.taskTitle(),
                state.updatedAt());
    }

    private void audit(
            String sessionId,
            TransitionResult result,
            TaskTransitionTriggerSource triggerSource,
            TaskState attemptedTarget) {
        TaskState previous = result.previousState();
        TaskState updated = result.newState();
        TaskPhase toPhase = result.accepted()
                ? (updated != null ? updated.phase() : null)
                : (attemptedTarget != null ? attemptedTarget.phase() : updated != null ? updated.phase() : null);
        String toStep = result.accepted()
                ? (updated != null ? updated.currentStep() : null)
                : (attemptedTarget != null ? attemptedTarget.currentStep() : updated != null ? updated.currentStep() : null);
        transitionRepository.insert(
                sessionId,
                result.transitionType(),
                previous != null ? previous.phase() : null,
                toPhase,
                previous != null ? previous.currentStep() : null,
                toStep,
                triggerSource,
                result.accepted(),
                result.rejectionCode(),
                result.rejectionReason());
    }

    private void audit(
            String sessionId,
            TransitionResult result,
            TaskTransitionTriggerSource triggerSource) {
        audit(sessionId, result, triggerSource, result.newState());
    }

    private void auditRejectedSkip(
            String sessionId,
            TaskState current,
            TaskTransitionGraph.RejectedSkipAttempt attempt) {
        transitionRepository.insert(
                sessionId,
                attempt.attemptedType(),
                attempt.fromPhase(),
                attempt.toPhase(),
                current.currentStep(),
                current.currentStep(),
                TaskTransitionTriggerSource.RULE,
                false,
                attempt.rejectionCode(),
                attempt.rejectionReason());
    }

    private TaskState copyWithSessionId(TaskState state, String sessionId) {
        return new TaskState(
                sessionId,
                state.phase(),
                state.currentStep(),
                state.expectedAction(),
                state.paused(),
                state.taskTitle(),
                state.updatedAt());
    }

    private TaskState targetPhaseSnapshot(TaskState current, TaskPhase phase, TaskState contentSource) {
        if (phase == null || contentSource == null) {
            return contentSource;
        }
        return new TaskState(
                current.sessionId(),
                phase,
                contentSource.currentStep(),
                contentSource.expectedAction(),
                current.paused(),
                current.taskTitle(),
                current.updatedAt());
    }

    private String restoreExpectedAction(TaskState current) {
        return switch (current.phase()) {
            case PLANNING -> PlanningSteps.isAgreement(current.currentStep(), current.expectedAction())
                    ? "Кратко предложить план и спросить подтверждение"
                    : "Задать уточняющие вопросы и согласовать план перед разбором";
            case EXECUTION -> "Продолжить разбор текущей темы";
            case VALIDATION -> "Задать один вопрос с вариантами A, B, C, D";
            case DONE -> "Подвести итог и предложить следующую тему";
        };
    }
}

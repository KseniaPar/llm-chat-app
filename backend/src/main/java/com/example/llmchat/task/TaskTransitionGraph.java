package com.example.llmchat.task;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class TaskTransitionGraph {

    private static final Pattern FINISH_WITHOUT_VALIDATION = Pattern.compile(
            "(?:^|[\\s,.!?;:—–-])\\s*"
                    + "(?:законч(?:и|ить|им)(?:\\s+(?:задач(?:у|и|ей|a)?|урок|занят(?:ие|ия)?))?"
                    + "|заверш(?:и|ить|им)(?:\\s+(?:задач(?:у|и|ей|a)?|урок|занят(?:ие|ия)?))?"
                    + "|(?:законч|заверш)(?:и|ить|им)?\\s+без\\s+(?:теста|самопровер(?:ки|ку|им)?|проверк(?:и|у|им)?)"
                    + "|(?:всё|все)\\s+понял"
                    + "|можно\\s+заканчивать"
                    + "|финал"
                    + "|готово\\s+(?:всё|все))",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SKIP_TO_VALIDATION = Pattern.compile(
            "(?:^|[\\s,.!?;:—–-])"
                    + "(?:сразу\\s+(?:задай\\s+)?(?:тест|mcq|самопровер)|"
                    + "задай\\s+(?:\\d+\\s+)?(?:тест|вопрос)|"
                    + "без\\s+плана|план\\s+не\\s+нужен|"
                    + "тест\\s+[A-Da-dА-Га-г](?:\\s+[A-Da-dА-Га-г]){2,3}|"
                    + "тест\\s+a\\s*b\\s*c\\s*d)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public boolean looksLikeSkipToValidation(String userMessage) {
        return userMessage != null && !userMessage.isBlank()
                && SKIP_TO_VALIDATION.matcher(userMessage.trim()).find();
    }

    public boolean looksLikeFinishWithoutValidation(String userMessage) {
        return userMessage != null && !userMessage.isBlank()
                && FINISH_WITHOUT_VALIDATION.matcher(userMessage.trim()).find();
    }

    public TaskTransitionValidation validate(
            TaskState current,
            TaskTransitionType type,
            TaskPhase targetPhase,
            TaskTransitionContext context) {
        if (type == TaskTransitionType.START_PLANNING) {
            return current == null
                    ? TaskTransitionValidation.ok()
                    : TaskTransitionValidation.rejected(
                            TaskTransitionRejectionCode.INVALID_TRANSITION,
                            "Задача уже начата.");
        }
        if (current == null) {
            return TaskTransitionValidation.rejected(
                    TaskTransitionRejectionCode.NO_TASK_STATE,
                    TaskTransitionRejectionCode.NO_TASK_STATE.defaultMessage());
        }

        return switch (type) {
            case PAUSE -> current.paused()
                    ? TaskTransitionValidation.rejected(
                            TaskTransitionRejectionCode.ALREADY_PAUSED,
                            TaskTransitionRejectionCode.ALREADY_PAUSED.defaultMessage())
                    : TaskTransitionValidation.ok();
            case RESUME -> !current.paused()
                    ? TaskTransitionValidation.rejected(
                            TaskTransitionRejectionCode.NOT_ON_PAUSE,
                            TaskTransitionRejectionCode.NOT_ON_PAUSE.defaultMessage())
                    : TaskTransitionValidation.ok();
            case ADVANCE_PLANNING_SUBPHASE -> validateAdvancePlanningSubPhase(current, context);
            case APPROVE_PLAN_TO_EXECUTION -> validateApprovePlan(current, context);
            case EXECUTION_TO_VALIDATION -> validateExecutionToValidation(current, context);
            case VALIDATION_TO_DONE -> validateValidationToDone(current);
            case UPDATE_IN_PHASE -> validateUpdateInPhase(current, targetPhase);
            case START_PLANNING -> TaskTransitionValidation.rejected(
                    TaskTransitionRejectionCode.INVALID_TRANSITION,
                    "Задача уже начата.");
        };
    }

    public Optional<RejectedSkipAttempt> detectUserSkipAttempt(TaskState current, String userMessage) {
        if (current == null || userMessage == null || userMessage.isBlank()) {
            return Optional.empty();
        }
        String trimmed = userMessage.trim();
        if (current.phase() == TaskPhase.PLANNING && SKIP_TO_VALIDATION.matcher(trimmed).find()) {
            return Optional.of(new RejectedSkipAttempt(
                    TaskTransitionType.EXECUTION_TO_VALIDATION,
                    TaskPhase.PLANNING,
                    TaskPhase.VALIDATION,
                    TaskTransitionRejectionCode.SKIP_NOT_ALLOWED,
                    "Нельзя перейти к самопроверке до утверждённого плана и разбора тем."));
        }
        if (current.phase() == TaskPhase.EXECUTION && looksLikeFinishWithoutValidation(trimmed)) {
            return Optional.of(new RejectedSkipAttempt(
                    TaskTransitionType.VALIDATION_TO_DONE,
                    TaskPhase.EXECUTION,
                    TaskPhase.DONE,
                    TaskTransitionRejectionCode.SKIP_NOT_ALLOWED,
                    "Нельзя завершить задачу без этапа самопроверки."));
        }
        if (current.phase() == TaskPhase.PLANNING && looksLikeFinishWithoutValidation(trimmed)) {
            return Optional.of(new RejectedSkipAttempt(
                    TaskTransitionType.VALIDATION_TO_DONE,
                    TaskPhase.PLANNING,
                    TaskPhase.DONE,
                    TaskTransitionRejectionCode.SKIP_NOT_ALLOWED,
                    "Нельзя завершить задачу без плана, разбора тем и самопроверки."));
        }
        if (current.phase() == TaskPhase.VALIDATION
                && looksLikeFinishWithoutValidation(trimmed)
                && !TaskStateTransitions.validationReadyToFinish(current)) {
            return Optional.of(new RejectedSkipAttempt(
                    TaskTransitionType.VALIDATION_TO_DONE,
                    TaskPhase.VALIDATION,
                    TaskPhase.DONE,
                    TaskTransitionRejectionCode.VALIDATION_INCOMPLETE,
                    TaskTransitionRejectionCode.VALIDATION_INCOMPLETE.defaultMessage()));
        }
        if (current.phase() == TaskPhase.PLANNING
                && targetPhaseFromMessage(trimmed) == TaskPhase.EXECUTION
                && !TaskStateTransitions.readyForExecution(trimmed, current)) {
            return Optional.of(new RejectedSkipAttempt(
                    TaskTransitionType.APPROVE_PLAN_TO_EXECUTION,
                    TaskPhase.PLANNING,
                    TaskPhase.EXECUTION,
                    TaskTransitionRejectionCode.PLAN_NOT_APPROVED,
                    "Нельзя начать разбор тем без подтверждения плана."));
        }
        return Optional.empty();
    }

    public TaskTransitionValidation validatePhaseChange(
            TaskState current,
            TaskPhase proposedPhase,
            TaskTransitionContext context) {
        if (current == null || proposedPhase == null || proposedPhase == current.phase()) {
            return TaskTransitionValidation.ok();
        }
        if (current.paused()) {
            return TaskTransitionValidation.rejected(
                    TaskTransitionRejectionCode.PAUSED_BLOCKS_PHASE,
                    "На паузе смена этапа запрещена.");
        }
        if (proposedPhase.ordinal() < current.phase().ordinal()) {
            return TaskTransitionValidation.rejected(
                    TaskTransitionRejectionCode.ROLLBACK_NOT_ALLOWED,
                    "Возврат с " + current.phase().id() + " на " + proposedPhase.id() + " запрещён.");
        }
        if (proposedPhase.ordinal() > current.phase().ordinal() + 1) {
            return TaskTransitionValidation.rejected(
                    TaskTransitionRejectionCode.SKIP_NOT_ALLOWED,
                    "Пропуск этапа: " + current.phase().id() + " → " + proposedPhase.id() + " не допускается.");
        }

        TaskTransitionType type = transitionTypeForPhaseChange(current.phase(), proposedPhase);
        if (type == null) {
            return TaskTransitionValidation.rejected(
                    TaskTransitionRejectionCode.INVALID_TRANSITION,
                    "Неизвестный переход: " + current.phase().id() + " → " + proposedPhase.id());
        }
        return validate(current, type, proposedPhase, context);
    }

    public List<TaskTransitionType> allowedNext(TaskState current) {
        if (current == null) {
            return List.of(TaskTransitionType.START_PLANNING);
        }
        List<TaskTransitionType> allowed = new ArrayList<>();
        if (current.paused()) {
            allowed.add(TaskTransitionType.RESUME);
            allowed.add(TaskTransitionType.UPDATE_IN_PHASE);
            return List.copyOf(allowed);
        }
        allowed.add(TaskTransitionType.UPDATE_IN_PHASE);
        if (!current.paused()) {
            allowed.add(TaskTransitionType.PAUSE);
        }
        switch (current.phase()) {
            case PLANNING -> {
                if (!PlanningSteps.isAgreement(current.currentStep(), current.expectedAction())) {
                    allowed.add(TaskTransitionType.ADVANCE_PLANNING_SUBPHASE);
                }
                if (PlanningSteps.isAgreement(current.currentStep(), current.expectedAction())) {
                    allowed.add(TaskTransitionType.APPROVE_PLAN_TO_EXECUTION);
                }
            }
            case EXECUTION -> allowed.add(TaskTransitionType.EXECUTION_TO_VALIDATION);
            case VALIDATION -> {
                if (TaskStateTransitions.validationReadyToFinish(current)) {
                    allowed.add(TaskTransitionType.VALIDATION_TO_DONE);
                }
            }
            case DONE -> {
                // terminal
            }
        }
        return List.copyOf(allowed);
    }

    public TaskTransitionType transitionTypeForPhaseChange(TaskPhase from, TaskPhase to) {
        if (from == to) {
            return TaskTransitionType.UPDATE_IN_PHASE;
        }
        return switch (from) {
            case PLANNING -> to == TaskPhase.EXECUTION ? TaskTransitionType.APPROVE_PLAN_TO_EXECUTION : null;
            case EXECUTION -> to == TaskPhase.VALIDATION ? TaskTransitionType.EXECUTION_TO_VALIDATION : null;
            case VALIDATION -> to == TaskPhase.DONE ? TaskTransitionType.VALIDATION_TO_DONE : null;
            case DONE -> null;
        };
    }

    public TaskTransitionType resolveAttemptedType(TaskPhase from, TaskPhase to) {
        TaskTransitionType direct = transitionTypeForPhaseChange(from, to);
        if (direct != null) {
            return direct;
        }
        if (from == null || to == null || from == to) {
            return TaskTransitionType.UPDATE_IN_PHASE;
        }
        if (to.ordinal() > from.ordinal()) {
            return switch (to) {
                case EXECUTION -> TaskTransitionType.APPROVE_PLAN_TO_EXECUTION;
                case VALIDATION -> TaskTransitionType.EXECUTION_TO_VALIDATION;
                case DONE -> TaskTransitionType.VALIDATION_TO_DONE;
                default -> TaskTransitionType.UPDATE_IN_PHASE;
            };
        }
        return TaskTransitionType.UPDATE_IN_PHASE;
    }

    private TaskTransitionValidation validateAdvancePlanningSubPhase(
            TaskState current,
            TaskTransitionContext context) {
        if (current.phase() != TaskPhase.PLANNING) {
            return TaskTransitionValidation.rejected(
                    TaskTransitionRejectionCode.INVALID_TRANSITION,
                    "Подэтап планирования доступен только на этапе planning.");
        }
        if (PlanningSteps.isAgreement(current.currentStep(), current.expectedAction())) {
            return TaskTransitionValidation.rejected(
                    TaskTransitionRejectionCode.GUARD_FAILED,
                    "Уже на подэтапе согласования плана.");
        }
        if (context.priorMessageCount() < 2
                || context.userMessage() == null
                || context.userMessage().trim().length() < 20) {
            return TaskTransitionValidation.rejected(
                    TaskTransitionRejectionCode.GUARD_FAILED,
                    "Недостаточно контекста для перехода к согласованию плана.");
        }
        return TaskTransitionValidation.ok();
    }

    private TaskTransitionValidation validateApprovePlan(
            TaskState current,
            TaskTransitionContext context) {
        if (current.phase() != TaskPhase.PLANNING) {
            return TaskTransitionValidation.rejected(
                    TaskTransitionRejectionCode.INVALID_TRANSITION,
                    "Утверждение плана доступно только на этапе planning.");
        }
        if (!PlanningSteps.isAgreement(current.currentStep(), current.expectedAction())) {
            return TaskTransitionValidation.rejected(
                    TaskTransitionRejectionCode.GUARD_FAILED,
                    "Сначала нужно перейти к согласованию плана.");
        }
        if (!TaskStateTransitions.readyForExecution(context.userMessage(), current)) {
            return TaskTransitionValidation.rejected(
                    TaskTransitionRejectionCode.PLAN_NOT_APPROVED,
                    TaskTransitionRejectionCode.PLAN_NOT_APPROVED.defaultMessage());
        }
        return TaskTransitionValidation.ok();
    }

    private TaskTransitionValidation validateExecutionToValidation(
            TaskState current,
            TaskTransitionContext context) {
        if (current.phase() != TaskPhase.EXECUTION) {
            return TaskTransitionValidation.rejected(
                    TaskTransitionRejectionCode.INVALID_TRANSITION,
                    "Переход к самопроверке доступен только с этапа execution.");
        }
        if (current.paused()) {
            return TaskTransitionValidation.rejected(
                    TaskTransitionRejectionCode.PAUSED_BLOCKS_PHASE,
                    TaskTransitionRejectionCode.PAUSED_BLOCKS_PHASE.defaultMessage());
        }
        boolean userWants = context.userMessage() != null
                && TaskStateTransitions.readyForValidation(context.userMessage());
        boolean executionDone = TaskStateTransitions.executionComplete(current);
        if (context.llmProposal() || context.autoTrigger() || userWants || executionDone) {
            return TaskTransitionValidation.ok();
        }
        return TaskTransitionValidation.rejected(
                TaskTransitionRejectionCode.GUARD_FAILED,
                "Разбор тем ещё не завершён.");
    }

    private TaskTransitionValidation validateValidationToDone(TaskState current) {
        if (current.phase() != TaskPhase.VALIDATION) {
            return TaskTransitionValidation.rejected(
                    TaskTransitionRejectionCode.INVALID_TRANSITION,
                    "Завершение доступно только с этапа validation.");
        }
        if (!TaskStateTransitions.validationReadyToFinish(current)) {
            return TaskTransitionValidation.rejected(
                    TaskTransitionRejectionCode.VALIDATION_INCOMPLETE,
                    TaskTransitionRejectionCode.VALIDATION_INCOMPLETE.defaultMessage());
        }
        return TaskTransitionValidation.ok();
    }

    private TaskTransitionValidation validateUpdateInPhase(TaskState current, TaskPhase targetPhase) {
        if (targetPhase != null && targetPhase != current.phase()) {
            return validatePhaseChange(current, targetPhase, TaskTransitionContext.empty());
        }
        return TaskTransitionValidation.ok();
    }

    private TaskPhase targetPhaseFromMessage(String message) {
        String lower = message.toLowerCase();
        if (lower.contains("объясн") || lower.contains("расскаж") || lower.contains("разбор")) {
            return TaskPhase.EXECUTION;
        }
        return null;
    }

    public record RejectedSkipAttempt(
            TaskTransitionType attemptedType,
            TaskPhase fromPhase,
            TaskPhase toPhase,
            TaskTransitionRejectionCode rejectionCode,
            String rejectionReason) {
    }
}

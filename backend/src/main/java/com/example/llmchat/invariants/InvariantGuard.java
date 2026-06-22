package com.example.llmchat.invariants;

import com.example.llmchat.personalization.UserProfile;
import com.example.llmchat.task.PlanningSteps;
import com.example.llmchat.task.TaskPhase;
import com.example.llmchat.task.TaskState;
import com.example.llmchat.task.TaskStateService;
import com.example.llmchat.task.TaskStateTransitions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class InvariantGuard {

    private static final String WORD_START = "(?:^|[\\s,.!?;:—–-])\\s*";
    private static final Pattern MATERIAL_REQUEST = Pattern.compile(
            WORD_START + "(?:объясни|расскажи|разбери|поясни|опиши|расскажи\\s+про|разбор\\s+тем)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ACADEMIC_CHEAT = Pattern.compile(
            "\\b(спиши|напиши\\s+за\\s+меня|готов(?:ое|ую|ый)?\\s+(?:эссе|работу|ответ)|"
                    + "для\\s+сдачи|сдай\\s+за\\s+меня)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern EARLY_MCQ_REQUEST = Pattern.compile(
            "(?:^|[\\s,.!?;:—–-])"
                    + "(?:самопровер|задай\\s+(?:\\d+\\s+)?тест|тест\\s+(?:с\\s+)?вариант|"
                    + "вариант(?:ы|ами)?\\s+[A-Da-dА-Га-г]|"
                    + "тест\\s+[A-Da-dА-Га-г](?:\\s+[A-Da-dА-Га-г]){2,3}|"
                    + "[A-Da-dА-Га-г]\\s*[)\\.]?\\s*[A-Da-dА-Га-г])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern PHASE_ROLLBACK = Pattern.compile(
            "верн[^\\s]*\\s+к\\s+разбор|ещё\\s+раз\\s+(?:объясни|разбери)|начни\\s+разбор\\s+заново|верн[^\\s]*\\s+к\\s+темам",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SKIP_TOPIC = Pattern.compile(
            "перескоч|пропуст(?:им|ить)|сразу\\s+к\\s+(?:теме|пункту)|перейд[^\\s]*\\s+к\\s+теме",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern BULK_LECTURE = Pattern.compile(
            WORD_START + "(?:сразу\\s+(?:расскажи|объясни|разбери)|все\\s+\\d+\\s+тем|"
                    + "полн(?:ый|ую)\\s+(?:разбор|лекцию)|весь\\s+материал|все\\s+темы\\s+лекции)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern LONG_CONTENT_REQUEST = Pattern.compile(
            "\\b(развёрнут|развернут|на\\s+\\d+\\s+страниц|большой\\s+конспект|"
                    + "подробн(?:ый|ую)\\s+на\\s+\\d+|длинн(?:ый|ую)\\s+конспект)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern LECTURE_DURING_VALIDATION = Pattern.compile(
            "\\b(объясни\\s+(?:заново|снова|весь|всё)|полный\\s+разбор|"
                    + "расскажи\\s+про\\s+тему|начни\\s+лекцию)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern BRIEF_CONSTRAINT = Pattern.compile(
            "\\b(кратко|до\\s+\\d+\\s+предложен|без\\s+воды|коротко)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern FINISH_WITHOUT_VALIDATION = Pattern.compile(
            WORD_START
                    + "(?:законч(?:и|ить|им)(?:\\s+(?:задач(?:у|и|ей|a)?|урок|занят(?:ие|ия)?))?"
                    + "|заверш(?:и|ить|им)(?:\\s+(?:задач(?:у|и|ей|a)?|урок|занят(?:ие|ия)?))?"
                    + "|(?:законч|заверш)(?:и|ить|им)?\\s+без\\s+(?:теста|самопровер(?:ки|ку|им)?|проверк(?:и|у|им)?)"
                    + "|(?:всё|все)\\s+понял"
                    + "|можно\\s+заканчивать"
                    + "|финал"
                    + "|готово\\s+(?:всё|все))",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final InvariantsService invariantsService;
    private final TaskStateService taskStateService;

    public InvariantGuard(
            InvariantsService invariantsService,
            TaskStateService taskStateService) {
        this.invariantsService = invariantsService;
        this.taskStateService = taskStateService;
    }

    public InvariantCheckResult check(InvariantContext context) {
        List<InvariantDefinition> matched = new ArrayList<>();
        List<InvariantDefinition> hardBlocked = new ArrayList<>();
        List<String> softHints = new ArrayList<>();

        for (InvariantDefinition rule : invariantsService.resolveActive(context)) {
            if (!isViolation(rule, context)) {
                continue;
            }
            matched.add(rule);
            if (rule.hardBlock() && rule.guard() != InvariantGuardType.REFUSAL_FORMAT) {
                hardBlocked.add(rule);
            } else if (!rule.hardBlock()) {
                softHints.add("Запрос может нарушать " + rule.id() + " («" + rule.title() + "») — проверь и откажи при необходимости.");
            }
        }
        return new InvariantCheckResult(List.copyOf(matched), List.copyOf(hardBlocked), List.copyOf(softHints));
    }

    public String formatRefusal(List<InvariantDefinition> violated) {
        if (violated.isEmpty()) {
            return "Я не могу выполнить этот запрос: он нарушает правила учебного процесса.";
        }
        InvariantDefinition primary = violated.get(0);
        StringBuilder builder = new StringBuilder();
        builder.append("Я не могу выполнить этот запрос: он нарушает инвариант **")
                .append(primary.id())
                .append("** («")
                .append(primary.title())
                .append("»). ");
        if (primary.description() != null && !primary.description().isBlank()) {
            builder.append(primary.description().trim()).append(" ");
        }
        if (primary.refusalHint() != null && !primary.refusalHint().isBlank()) {
            builder.append("Могу предложить: ").append(primary.refusalHint().trim());
        }
        return builder.toString().trim();
    }

    private boolean isViolation(InvariantDefinition rule, InvariantContext context) {
        if (rule.guard() == InvariantGuardType.NONE || rule.guard() == InvariantGuardType.REFUSAL_FORMAT) {
            return false;
        }
        String message = context.userMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        Optional<TaskState> taskState = context.taskState();
        return switch (rule.guard()) {
            case VALIDATION_BEFORE_EXECUTION_COMPLETE -> matchesEarlyValidation(context, taskState, message);
            case EXECUTION_WITHOUT_CONSENT -> matchesExecutionWithoutConsent(taskState, message);
            case MATERIAL_DURING_CLARIFICATION -> matchesMaterialDuringClarification(taskState, message);
            case MATERIAL_DURING_AGREEMENT -> matchesMaterialDuringAgreement(taskState, message);
            case ACADEMIC_INTEGRITY -> ACADEMIC_CHEAT.matcher(message.trim()).find();
            case PHASE_ROLLBACK_REQUEST -> matchesPhaseRollback(taskState, message);
            case SKIP_TOPIC_IN_EXECUTION -> matchesSkipTopic(taskState, message);
            case FINISH_WITHOUT_VALIDATION -> matchesFinishWithoutValidation(taskState, message);
            case LECTURE_DURING_VALIDATION -> matchesLectureDuringValidation(taskState, message);
            case STRUCTURED_PREP_WITHOUT_PLANNING -> matchesStructuredPrepWithoutPlanning(context, taskState, message);
            case PROFILE_CONSTRAINT_VIOLATION -> matchesProfileConstraintViolation(context.profile(), message);
            default -> false;
        };
    }

    private boolean matchesEarlyValidation(
            InvariantContext context,
            Optional<TaskState> taskState,
            String message) {
        if (!TaskStateTransitions.readyForValidation(message)
                && !EARLY_MCQ_REQUEST.matcher(message.trim()).find()) {
            return false;
        }
        if (taskState.isEmpty()) {
            return true;
        }
        TaskState state = taskState.get();
        if (state.phase() == TaskPhase.PLANNING) {
            return true;
        }
        if (state.phase() == TaskPhase.EXECUTION && !TaskStateTransitions.executionComplete(state)) {
            return true;
        }
        return false;
    }

    private boolean matchesExecutionWithoutConsent(Optional<TaskState> taskState, String message) {
        if (taskState.isEmpty() || taskState.get().phase() != TaskPhase.PLANNING) {
            return false;
        }
        if (!MATERIAL_REQUEST.matcher(message.trim()).find()) {
            return false;
        }
        TaskState state = taskState.get();
        return !TaskStateTransitions.readyForExecution(message, state);
    }

    private boolean matchesMaterialDuringClarification(Optional<TaskState> taskState, String message) {
        if (taskState.isEmpty() || taskState.get().phase() != TaskPhase.PLANNING) {
            return false;
        }
        TaskState state = taskState.get();
        if (PlanningSteps.isAgreement(state.currentStep(), state.expectedAction())) {
            return false;
        }
        return MATERIAL_REQUEST.matcher(message.trim()).find()
                || BULK_LECTURE.matcher(message.trim()).find();
    }

    private boolean matchesMaterialDuringAgreement(Optional<TaskState> taskState, String message) {
        if (taskState.isEmpty() || taskState.get().phase() != TaskPhase.PLANNING) {
            return false;
        }
        TaskState state = taskState.get();
        if (!PlanningSteps.isAgreement(state.currentStep(), state.expectedAction())) {
            return false;
        }
        return MATERIAL_REQUEST.matcher(message.trim()).find();
    }

    private boolean matchesPhaseRollback(Optional<TaskState> taskState, String message) {
        if (taskState.isEmpty()) {
            return false;
        }
        TaskPhase phase = taskState.get().phase();
        if (phase != TaskPhase.VALIDATION && phase != TaskPhase.DONE) {
            return false;
        }
        return PHASE_ROLLBACK.matcher(message.trim()).find();
    }

    private boolean matchesSkipTopic(Optional<TaskState> taskState, String message) {
        if (taskState.isEmpty() || taskState.get().phase() != TaskPhase.EXECUTION) {
            return false;
        }
        return SKIP_TOPIC.matcher(message.trim()).find();
    }

    private boolean matchesFinishWithoutValidation(Optional<TaskState> taskState, String message) {
        if (taskState.isEmpty() || !FINISH_WITHOUT_VALIDATION.matcher(message.trim()).find()) {
            return false;
        }
        TaskState state = taskState.get();
        if (state.phase() == TaskPhase.DONE) {
            return false;
        }
        if (state.phase() == TaskPhase.VALIDATION) {
            return !TaskStateTransitions.validationReadyToFinish(state);
        }
        return state.phase() == TaskPhase.PLANNING || state.phase() == TaskPhase.EXECUTION;
    }

    private boolean matchesLectureDuringValidation(Optional<TaskState> taskState, String message) {
        if (taskState.isEmpty() || taskState.get().phase() != TaskPhase.VALIDATION) {
            return false;
        }
        return LECTURE_DURING_VALIDATION.matcher(message.trim()).find()
                || (MATERIAL_REQUEST.matcher(message.trim()).find()
                && !TaskStateTransitions.isMcqAnswer(message));
    }

    private boolean matchesStructuredPrepWithoutPlanning(
            InvariantContext context,
            Optional<TaskState> taskState,
            String message) {
        if (!BULK_LECTURE.matcher(message.trim()).find() && !MATERIAL_REQUEST.matcher(message.trim()).find()) {
            return false;
        }
        if (!taskStateService.looksLikeStudyTaskStart(message)) {
            return false;
        }
        if (taskState.isEmpty()) {
            return true;
        }
        TaskState state = taskState.get();
        if (state.phase() == TaskPhase.PLANNING) {
            return !TaskStateTransitions.readyForExecution(message, state);
        }
        return false;
    }

    private boolean matchesProfileConstraintViolation(UserProfile profile, String message) {
        if (profile == null || profile.constraints() == null || profile.constraints().isBlank()) {
            return false;
        }
        String constraints = profile.constraints().toLowerCase();
        if (!BRIEF_CONSTRAINT.matcher(constraints).find()) {
            return false;
        }
        return LONG_CONTENT_REQUEST.matcher(message.trim()).find();
    }
}

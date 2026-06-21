package com.example.llmchat.task;

import com.example.llmchat.dto.TaskStateSnapshot;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class TaskStateService {

    private static final Pattern PAUSE_PATTERN = Pattern.compile(
            "\\b(пауза|приостанов(?:и|ить|ка)?|стоп|останов(?:и|ить|ись)?)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern RESUME_PATTERN = Pattern.compile(
            "\\b(продолж(?:аем|ай|ить|им)?|возобнов(?:и|ить|ляем)?|давай\\s+дальше|готов\\s+продолжать)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern STUDY_TASK_START = Pattern.compile(
            "\\b(помог(?:и|ите)|подготов(?:иться|ка|люсь)?|экзамен|контрольн|зачёт|"
                    + "к\\s+(?:тесту|экзамену|контрольной)|разобра(?:ть|ться)|выучи(?:ть|м)|"
                    + "конспект|готовлюсь|готовимся|учебн|занят(?:ие|ия))\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final TaskStateRepository taskStateRepository;
    private final ConcurrentHashMap<String, TaskState> pendingBySession = new ConcurrentHashMap<>();

    public TaskStateService(TaskStateRepository taskStateRepository) {
        this.taskStateRepository = taskStateRepository;
    }

    public void promotePendingState(String sessionId) {
        TaskState pending = pendingBySession.remove(sessionId);
        if (pending != null) {
            taskStateRepository.upsert(pending);
        }
    }

    public void deferStateUpdate(TaskState state) {
        pendingBySession.put(state.sessionId(), state);
    }

    public void clearPendingState(String sessionId) {
        if (sessionId != null) {
            pendingBySession.remove(sessionId);
        }
    }

    public Optional<TaskState> getState(String sessionId) {
        return taskStateRepository.findBySessionId(sessionId);
    }

    public Optional<TaskState> bootstrapPlanningIfNeeded(String sessionId, String userMessage) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        if (taskStateRepository.findBySessionId(sessionId).isPresent()) {
            return Optional.empty();
        }
        if (!looksLikeStudyTaskStart(userMessage)) {
            return Optional.empty();
        }
        TaskState initial = TaskState.initialPlanning(sessionId, inferTaskTitle(userMessage));
        return Optional.of(taskStateRepository.upsert(initial));
    }

    public boolean looksLikeStudyTaskStart(String userMessage) {
        return userMessage != null && STUDY_TASK_START.matcher(userMessage.trim()).find();
    }

    private String inferTaskTitle(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return "Подготовка к занятию";
        }
        String trimmed = userMessage.trim();
        if (trimmed.length() <= 80) {
            return trimmed;
        }
        return trimmed.substring(0, 77) + "...";
    }

    public Optional<TaskState> advancePlanningSubPhase(String sessionId, String userMessage, int priorMessageCount) {
        Optional<TaskState> existing = taskStateRepository.findBySessionId(sessionId);
        if (existing.isEmpty() || existing.get().phase() != TaskPhase.PLANNING) {
            return Optional.empty();
        }
        TaskState state = existing.get();
        if (PlanningSteps.isAgreement(state.currentStep(), state.expectedAction())) {
            return Optional.empty();
        }
        if (priorMessageCount < 2 || userMessage == null || userMessage.trim().length() < 20) {
            return Optional.empty();
        }
        TaskState agreement = new TaskState(
                sessionId,
                TaskPhase.PLANNING,
                PlanningSteps.AGREEMENT,
                "Кратко предложить план из 3–5 пунктов и спросить: «Начнём по этому плану?»",
                state.paused(),
                state.taskTitle(),
                state.updatedAt());
        return Optional.of(taskStateRepository.upsert(agreement));
    }

    public Optional<TaskState> confirmExecutionIfReady(String sessionId, String userMessage) {
        Optional<TaskState> existing = taskStateRepository.findBySessionId(sessionId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        TaskState state = existing.get();
        if (state.phase() != TaskPhase.PLANNING
                || !PlanningSteps.isAgreement(state.currentStep(), state.expectedAction())
                || !TaskStateTransitions.readyForExecution(userMessage, state)) {
            return Optional.empty();
        }
        TaskState execution = new TaskState(
                sessionId,
                TaskPhase.EXECUTION,
                "Разбор тем: первая тема по плану",
                "Объяснить первую тему из согласованного плана",
                state.paused(),
                state.taskTitle(),
                state.updatedAt());
        return Optional.of(taskStateRepository.upsert(execution));
    }

    public Optional<TaskState> startValidationIfReady(String sessionId, String userMessage) {
        if (!TaskStateTransitions.readyForValidation(userMessage)) {
            return Optional.empty();
        }
        Optional<TaskState> existing = taskStateRepository.findBySessionId(sessionId);
        if (existing.isEmpty() || existing.get().phase() != TaskPhase.EXECUTION) {
            return Optional.empty();
        }
        TaskState state = existing.get();
        TaskState validation = new TaskState(
                sessionId,
                TaskPhase.VALIDATION,
                "Самопроверка: вопрос 1 из 3",
                "Задать первый вопрос с вариантами A, B, C, D по пройденному материалу",
                state.paused(),
                state.taskTitle(),
                state.updatedAt());
        return Optional.of(taskStateRepository.upsert(validation));
    }

    public Optional<TaskState> advanceValidationAfterMcqAnswer(String sessionId, String userMessage) {
        if (!TaskStateTransitions.isMcqAnswer(userMessage)) {
            return Optional.empty();
        }
        Optional<TaskState> existing = taskStateRepository.findBySessionId(sessionId);
        if (existing.isEmpty() || existing.get().phase() != TaskPhase.VALIDATION) {
            return Optional.empty();
        }
        TaskState state = existing.get();
        int[] progress = TaskStateTransitions.parseValidationProgress(state.currentStep());
        int answered = progress[0];
        int total = progress[1];
        String step;
        String action;
        if (answered >= total) {
            step = "Самопроверка: вопрос " + total + " из " + total;
            action = "Дать краткий разбор последнего ответа и подвести итог самопроверки";
        } else {
            int next = answered + 1;
            step = "Самопроверка: вопрос " + next + " из " + total;
            action = "Дать краткий разбор ответа и задать вопрос " + next + " с вариантами A, B, C, D";
        }
        TaskState advanced = new TaskState(
                sessionId,
                TaskPhase.VALIDATION,
                step,
                action,
                state.paused(),
                state.taskTitle(),
                state.updatedAt());
        return Optional.of(taskStateRepository.upsert(advanced));
    }

    public Optional<TaskState> autoAdvanceToValidationIfReady(String sessionId) {
        Optional<TaskState> existing = taskStateRepository.findBySessionId(sessionId);
        if (existing.isEmpty() || existing.get().phase() != TaskPhase.EXECUTION || existing.get().paused()) {
            return Optional.empty();
        }
        TaskState state = existing.get();
        if (!TaskStateTransitions.executionComplete(state)) {
            return Optional.empty();
        }
        return Optional.of(taskStateRepository.upsert(toValidationState(state)));
    }

    public Optional<TaskState> autoAdvanceToDoneIfReady(String sessionId) {
        Optional<TaskState> existing = taskStateRepository.findBySessionId(sessionId);
        if (existing.isEmpty() || existing.get().phase() != TaskPhase.VALIDATION || existing.get().paused()) {
            return Optional.empty();
        }
        TaskState state = existing.get();
        if (!TaskStateTransitions.validationReadyToFinish(state)) {
            return Optional.empty();
        }
        return Optional.of(taskStateRepository.upsert(toDoneState(state)));
    }

    public TaskState toValidationState(TaskState base) {
        return new TaskState(
                base.sessionId(),
                TaskPhase.VALIDATION,
                "Самопроверка: вопрос 1 из 3",
                "Задать первый вопрос с вариантами A, B, C, D по пройденному материалу",
                base.paused(),
                base.taskTitle(),
                base.updatedAt());
    }

    public TaskState toDoneState(TaskState base) {
        return new TaskState(
                base.sessionId(),
                TaskPhase.DONE,
                "Тема пройдена",
                "Кратко подвести итог и предложить следующую тему",
                base.paused(),
                base.taskTitle(),
                base.updatedAt());
    }

    public TaskState saveState(TaskState state) {
        return taskStateRepository.upsert(state);
    }

    public TaskState pause(String sessionId) {
        TaskState current = requireState(sessionId);
        TaskState paused = new TaskState(
                sessionId,
                current.phase(),
                current.currentStep(),
                "Ожидание возобновления студентом",
                true,
                current.taskTitle(),
                current.updatedAt());
        return taskStateRepository.upsert(paused);
    }

    public TaskState resume(String sessionId) {
        TaskState current = requireState(sessionId);
        TaskState resumed = new TaskState(
                sessionId,
                current.phase(),
                current.currentStep(),
                restoreExpectedAction(current),
                false,
                current.taskTitle(),
                current.updatedAt());
        return taskStateRepository.upsert(resumed);
    }

    public PauseResumeCommand detectCommand(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return PauseResumeCommand.NONE;
        }
        String normalized = prompt.trim();
        if (PAUSE_PATTERN.matcher(normalized).find()) {
            return PauseResumeCommand.PAUSE;
        }
        if (RESUME_PATTERN.matcher(normalized).find()) {
            return PauseResumeCommand.RESUME;
        }
        return PauseResumeCommand.NONE;
    }

    public String formatTaskBlock(TaskState state) {
        if (state == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder("Состояние задачи:\n");
        if (state.taskTitle() != null && !state.taskTitle().isBlank()) {
            builder.append("- Задача: ").append(state.taskTitle().trim()).append("\n");
        }
        builder.append("- Этап: ").append(state.phase().id())
                .append(" (").append(state.phase().displayLabel()).append(")\n");
        builder.append("- Текущий шаг: ").append(state.currentStep().trim()).append("\n");
        builder.append("- Ожидаемое действие: ").append(state.expectedAction().trim()).append("\n");
        builder.append("- Пауза: ").append(state.paused() ? "да" : "нет").append("\n");
        builder.append(planningPhaseRules(state));
        builder.append(phaseRules(state.phase()));
        builder.append("""
                
                Общие правила:
                - При паузе не переходи к новому материалу; кратко напомни текущий шаг (1 предложение).
                - При возобновлении НЕ повторяй уже объяснённое — продолжай с текущего шага.
                - Следуй ожидаемому действию текущего этапа.
                """);
        return builder.toString().trim();
    }

    private String planningPhaseRules(TaskState state) {
        if (state.phase() != TaskPhase.PLANNING) {
            return "";
        }
        if (PlanningSteps.isAgreement(state.currentStep(), state.expectedAction())) {
            return """
                    Правила подэтапа «Согласование плана»:
                    - Кратко предложи план из 3–5 нумерованных пунктов по ответам студента.
                    - В конце спроси: «Начнём по этому плану?» или «Подходит?»
                    - НЕ объясняй учебный материал и НЕ задавай новые уточняющие вопросы.
                    """;
        }
        return """
                Правила подэтапа «Уточнение целей»:
                - Ответ = 2–4 нумерованных вопроса (цель, срок, уровень, слабые места, формат).
                - Одно короткое вступление допустимо; без плана и без объяснения темы.
                """;
    }

    private String phaseRules(TaskPhase phase) {
        return switch (phase) {
            case PLANNING -> """
                    Общее для этапа «Подготовка плана»: не начинай разбор тем, пока студент не подтвердил план.
                    """;
            case EXECUTION -> """
                    Правила этапа «Разбор тем»:
                    - Объясняй материал по текущему шагу; двигайся последовательно.
                    - После каждого блока кратко спроси, всё ли понятно, прежде чем идти дальше.
                    - Когда разобран последний пункт плана (N/M, N=M) — сам предложи самопроверку
                      и задай первый вопрос с вариантами A–D (не жди команды «проверь меня»).
                    """;
            case VALIDATION -> """
                    Правила этапа «Самопроверка»:
                    - За один ответ — РОВНО один вопрос с 4 вариантами (A, B, C, D). Не задавай несколько вопросов сразу.
                    - Формат ответа (строго):
                      **Вопрос N из M.** Текст вопроса?
                      A) первый вариант
                      B) второй вариант
                      C) третий вариант
                      D) четвёртый вариант
                      Ответьте буквой: A, B, C или D.
                    - Варианты короткие (до 12 слов); один вариант правильный, остальные правдоподобные.
                    - Если студент ответил буквой: 1–2 предложения — верно/неверно и почему; затем следующий вопрос в том же формате.
                    - После последнего вопроса (N=M) — краткий итог и завершение задачи (этап done), без ожидания «готов» от студента.
                    - Не вводи новые большие темы; не объясняй материал заново без необходимости.
                    """;
            case DONE -> """
                    Правила этапа «Тема пройдена»:
                    - Кратко подведи итог; предложи следующую тему или завершение сессии.
                    """;
        };
    }

    public List<String> buildTaskStateLogs(TaskState state, boolean appliedToPrompt) {
        List<String> logs = new ArrayList<>();
        if (state == null) {
            logs.add("TASK: задача не начата — блок состояния не добавлен");
            return logs;
        }
        if (appliedToPrompt) {
            logs.add("TASK: состояние добавлено в промпт");
        }
        logs.add("TASK → этап: " + state.phase().displayLabel());
        logs.add("TASK → шаг: " + state.currentStep());
        logs.add("TASK → ожидание: " + state.expectedAction());
        if (state.paused()) {
            logs.add("TASK → пауза: активна");
        }
        return logs;
    }

    public TaskStateSnapshot toSnapshot(TaskState state, boolean appliedToPrompt) {
        if (state == null) {
            return new TaskStateSnapshot(null, null, null, null, null, false, false, false);
        }
        return new TaskStateSnapshot(
                state.phase().id(),
                state.phase().displayLabel(),
                state.currentStep(),
                state.expectedAction(),
                state.taskTitle(),
                state.paused(),
                appliedToPrompt,
                true);
    }

    private TaskState requireState(String sessionId) {
        return taskStateRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Состояние задачи не найдено для сессии."));
    }

    private String restoreExpectedAction(TaskState current) {
        if (current.paused()) {
            return switch (current.phase()) {
                case PLANNING -> PlanningSteps.isAgreement(current.currentStep(), current.expectedAction())
                        ? "Кратко предложить план и спросить подтверждение"
                        : "Задать уточняющие вопросы и согласовать план перед разбором";
                case EXECUTION -> "Продолжить разбор текущей темы";
                case VALIDATION -> "Задать один вопрос с вариантами A, B, C, D";
                case DONE -> "Подвести итог и предложить следующую тему";
            };
        }
        return current.expectedAction();
    }

    public enum PauseResumeCommand {
        NONE,
        PAUSE,
        RESUME
    }
}

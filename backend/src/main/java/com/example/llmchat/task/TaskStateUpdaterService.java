package com.example.llmchat.task;

import com.example.llmchat.agent.CompletionResult;
import com.example.llmchat.agent.OpenRouterHttpClient;
import com.example.llmchat.dto.AgentChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class TaskStateUpdaterService {

    private final OpenRouterHttpClient openRouterHttpClient;
    private final TaskStateRepository taskStateRepository;
    private final TaskStateService taskStateService;
    private final TaskStateMachine taskStateMachine;
    private final ObjectMapper objectMapper;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final String updatePrompt;

    public TaskStateUpdaterService(
            OpenRouterHttpClient openRouterHttpClient,
            TaskStateRepository taskStateRepository,
            TaskStateService taskStateService,
            TaskStateMachine taskStateMachine,
            ObjectMapper objectMapper,
            @Value("${app.openrouter.model}") String model,
            @Value("${app.agent.temperature}") double temperature,
            @Value("${app.agent.max-tokens}") int maxTokens,
            @Value("${app.agent.task.update-prompt}") String updatePrompt) {
        this.openRouterHttpClient = openRouterHttpClient;
        this.taskStateRepository = taskStateRepository;
        this.taskStateService = taskStateService;
        this.taskStateMachine = taskStateMachine;
        this.objectMapper = objectMapper;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.updatePrompt = updatePrompt;
    }

    public Optional<TaskState> updateFromTurn(
            String sessionId,
            String userMessage,
            String assistantMessage,
            Map<String, String> workingFacts,
            List<AgentChatMessage> recentMessages) {
        Optional<TaskState> existing = taskStateRepository.findBySessionId(sessionId);
        if (existing.isPresent() && existing.get().paused()) {
            return existing;
        }

        StringBuilder userContent = new StringBuilder();
        if (existing.isPresent()) {
            TaskState state = existing.get();
            userContent.append("Текущее состояние задачи:\n");
            userContent.append("- phase: ").append(state.phase().id()).append("\n");
            userContent.append("- currentStep: ").append(state.currentStep()).append("\n");
            userContent.append("- expectedAction: ").append(state.expectedAction()).append("\n");
            if (state.taskTitle() != null) {
                userContent.append("- taskTitle: ").append(state.taskTitle()).append("\n");
            }
            userContent.append("\n");
        } else {
            userContent.append("Задача ещё не начата.\n\n");
        }
        if (workingFacts != null && !workingFacts.isEmpty()) {
            userContent.append("WORKING facts:\n");
            for (Map.Entry<String, String> entry : workingFacts.entrySet()) {
                userContent.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
            userContent.append("\n");
        }
        if (recentMessages != null && !recentMessages.isEmpty()) {
            userContent.append("Недавний контекст:\n");
            int from = Math.max(0, recentMessages.size() - 4);
            for (AgentChatMessage message : recentMessages.subList(from, recentMessages.size())) {
                userContent.append(message.role()).append(": ").append(message.content()).append("\n");
            }
            userContent.append("\n");
        }
        userContent.append("Новое сообщение пользователя:\n").append(userMessage).append("\n\n");
        userContent.append("Ответ ассистента:\n").append(assistantMessage);

        List<OpenRouterHttpClient.ChatMessage> request = List.of(
                new OpenRouterHttpClient.ChatMessage("system", updatePrompt),
                new OpenRouterHttpClient.ChatMessage("user", userContent.toString()));

        CompletionResult completion = openRouterHttpClient.complete(model, temperature, maxTokens, request, false);
        TaskStateMachine.TaskStateProposal parsed = parseProposal(completion.content());
        if (parsed == null) {
            return existing;
        }

        final TaskStateMachine.TaskStateProposal proposal =
                gatePhaseTransitions(existing, parsed, userMessage);

        TaskState base = existing.orElseGet(() -> TaskState.initialPlanning(
                sessionId,
                proposal.taskTitle() != null ? proposal.taskTitle() : "Подготовка к экзамену"));
        TaskState merged = taskStateMachine.applyProposal(base, proposal);
        merged = enrichPlanningState(merged, userMessage, existing);
        merged = enrichValidationState(merged, existing);
        merged = autoAdvanceAfterTurn(merged, assistantMessage, userMessage, existing);
        taskStateService.deferStateUpdate(merged);
        return Optional.of(merged);
    }

    private TaskState autoAdvanceAfterTurn(
            TaskState merged,
            String assistantMessage,
            String userMessage,
            Optional<TaskState> existing) {
        if (merged.paused()) {
            return merged;
        }
        if (merged.phase() == TaskPhase.DONE) {
            return merged;
        }
        if (merged.phase() == TaskPhase.EXECUTION
                && (TaskStateTransitions.executionComplete(merged)
                || TaskStateTransitions.assistantSuggestsValidation(assistantMessage))) {
            return taskStateService.toValidationState(merged);
        }
        if (merged.phase() == TaskPhase.VALIDATION && shouldFinishValidation(merged, assistantMessage, userMessage, existing)) {
            return taskStateService.toDoneState(merged);
        }
        return merged;
    }

    private boolean shouldFinishValidation(
            TaskState merged,
            String assistantMessage,
            String userMessage,
            Optional<TaskState> existing) {
        if (TaskStateTransitions.validationReadyToFinish(merged)) {
            return true;
        }
        if (TaskStateTransitions.assistantGivesSummary(assistantMessage)) {
            return true;
        }
        if (existing.isPresent()
                && existing.get().phase() == TaskPhase.VALIDATION
                && TaskStateTransitions.isMcqAnswer(userMessage)) {
            int[] progress = TaskStateTransitions.parseValidationProgress(existing.get().currentStep());
            return progress[0] >= progress[1];
        }
        return false;
    }

    private TaskState enrichPlanningState(
            TaskState merged,
            String userMessage,
            Optional<TaskState> existing) {
        if (merged.phase() != TaskPhase.PLANNING) {
            return merged;
        }
        if (PlanningSteps.isAgreement(merged.currentStep(), merged.expectedAction())) {
            return merged;
        }
        if (existing.isPresent()
                && PlanningSteps.isAgreement(existing.get().currentStep(), existing.get().expectedAction())) {
            return merged;
        }
        String step = merged.currentStep();
        String action = merged.expectedAction();
        if (step == null || step.isBlank()) {
            step = PlanningSteps.CLARIFICATION;
        }
        if (action == null || action.isBlank()) {
            action = "Задать 2–4 уточняющих вопроса (цель, срок, уровень, слабые места, формат занятий)";
        }
        return new TaskState(
                merged.sessionId(),
                merged.phase(),
                step,
                action,
                merged.paused(),
                merged.taskTitle(),
                merged.updatedAt());
    }

    private TaskStateMachine.TaskStateProposal gatePhaseTransitions(
            Optional<TaskState> existing,
            TaskStateMachine.TaskStateProposal parsed,
            String userMessage) {
        TaskStateMachine.TaskStateProposal gated = gatePlanningTransition(existing, parsed, userMessage);
        return gateValidationTransition(existing, gated, userMessage);
    }

    private TaskStateMachine.TaskStateProposal gateValidationTransition(
            Optional<TaskState> existing,
            TaskStateMachine.TaskStateProposal parsed,
            String userMessage) {
        if (existing.isEmpty() || existing.get().phase() != TaskPhase.EXECUTION) {
            return parsed;
        }
        TaskState execution = existing.get();
        boolean llmWantsValidation = parsed.phase() == TaskPhase.VALIDATION;
        boolean userWantsValidation = TaskStateTransitions.readyForValidation(userMessage);
        boolean executionDone = TaskStateTransitions.executionComplete(execution);
        if (!llmWantsValidation && !userWantsValidation && !executionDone) {
            return parsed;
        }
        if (llmWantsValidation) {
            return parsed;
        }
        return new TaskStateMachine.TaskStateProposal(
                TaskPhase.VALIDATION,
                "Самопроверка: вопрос 1 из 3",
                "Задать первый вопрос с вариантами A, B, C, D по пройденному материалу",
                parsed.taskTitle() != null ? parsed.taskTitle() : execution.taskTitle());
    }

    private TaskState enrichValidationState(
            TaskState merged,
            Optional<TaskState> existing) {
        if (merged.phase() != TaskPhase.VALIDATION) {
            return merged;
        }
        String step = merged.currentStep();
        String action = merged.expectedAction();

        if (existing.isPresent() && existing.get().phase() != TaskPhase.VALIDATION) {
            step = "Самопроверка: вопрос 1 из 3";
            action = "Задать первый вопрос с вариантами A, B, C, D по пройденному материалу";
        } else {
            if (step == null || step.isBlank()) {
                step = "Самопроверка: вопрос 1 из 3";
            }
            if (action == null || action.isBlank() || !mentionsMcqOptions(action)) {
                action = "Задать один вопрос с вариантами A, B, C, D по пройденному материалу";
            }
        }

        return new TaskState(
                merged.sessionId(),
                merged.phase(),
                step,
                action,
                merged.paused(),
                merged.taskTitle(),
                merged.updatedAt());
    }

    private boolean mentionsMcqOptions(String action) {
        String lower = action.toLowerCase();
        return lower.contains("a, b, c, d") || lower.contains("a–d") || lower.contains("a-d")
                || (lower.contains("вариант") && lower.contains(" a"));
    }

    private TaskStateMachine.TaskStateProposal gatePlanningTransition(
            Optional<TaskState> existing,
            TaskStateMachine.TaskStateProposal parsed,
            String userMessage) {
        if (existing.isPresent()
                && existing.get().phase() == TaskPhase.PLANNING
                && TaskStateTransitions.readyForExecution(userMessage, existing.get())) {
            return parsed;
        }
        if (existing.isEmpty() || existing.get().phase() != TaskPhase.PLANNING) {
            return parsed;
        }
        if (parsed.phase() == null || parsed.phase() == TaskPhase.PLANNING) {
            return parsed;
        }
        if (parsed.phase() == TaskPhase.EXECUTION && !TaskStateTransitions.readyForExecution(userMessage, existing.get())) {
            String step = PlanningSteps.isAgreement(parsed.currentStep(), parsed.expectedAction())
                    ? PlanningSteps.AGREEMENT
                    : (parsed.currentStep() != null && !parsed.currentStep().isBlank()
                            ? parsed.currentStep()
                            : PlanningSteps.CLARIFICATION);
            return new TaskStateMachine.TaskStateProposal(
                    TaskPhase.PLANNING,
                    step,
                    PlanningSteps.isAgreement(step, null)
                            ? "Кратко предложить план и спросить подтверждение перед разбором"
                            : "Задать уточняющие вопросы и получить подтверждение плана перед разбором",
                    parsed.taskTitle());
        }
        return parsed;
    }

    private TaskStateMachine.TaskStateProposal parseProposal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = raw.trim();
        if (json.startsWith("```")) {
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            String phaseText = root.path("phase").asText(null);
            if (root.path("startTask").asBoolean(false) == false
                    && (phaseText == null || phaseText.isBlank())
                    && root.path("currentStep").asText("").isBlank()
                    && root.path("expectedAction").asText("").isBlank()
                    && root.path("taskTitle").asText("").isBlank()) {
                return null;
            }
            TaskPhase phase = null;
            if (phaseText != null && !phaseText.isBlank()) {
                phase = TaskPhase.fromId(phaseText);
            }
            return new TaskStateMachine.TaskStateProposal(
                    phase,
                    root.path("currentStep").asText(null),
                    root.path("expectedAction").asText(null),
                    root.path("taskTitle").asText(null));
        } catch (Exception exception) {
            return null;
        }
    }
}

package com.example.llmchat.agent;

import com.example.llmchat.dto.AgentChatMessage;
import com.example.llmchat.dto.AgentRequest;
import com.example.llmchat.dto.AgentResponse;
import com.example.llmchat.dto.InvariantsSnapshot;
import com.example.llmchat.dto.McpToolCallLogDto;
import com.example.llmchat.dto.MemoryContextSnapshot;
import com.example.llmchat.dto.TaskStateSnapshot;
import com.example.llmchat.dto.TokenStats;
import com.example.llmchat.dto.UserProfileSnapshot;
import com.example.llmchat.invariants.InvariantCheckResult;
import com.example.llmchat.invariants.InvariantContext;
import com.example.llmchat.invariants.InvariantGuard;
import com.example.llmchat.invariants.InvariantsService;
import com.example.llmchat.memory.ContextAssembler;
import com.example.llmchat.memory.MemoryManager;
import com.example.llmchat.personalization.PersonalizationService;
import com.example.llmchat.personalization.UserProfile;
import com.example.llmchat.task.TaskState;
import com.example.llmchat.task.TaskStateService;
import com.example.llmchat.task.TaskStateUpdaterService;
import com.example.llmchat.task.TaskTransitionService;
import com.example.llmchat.task.TransitionResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ChatAgent {

    private static final double NEAR_LIMIT_RATIO = 0.85;
    private static final ContextStrategy INTERNAL_STRATEGY = ContextStrategy.SLIDING_WINDOW;

    private final OpenRouterHttpClient openRouterHttpClient;
    private final AgentChatCompletionService agentChatCompletionService;
    private final ConversationStore conversationStore;
    private final HttpExchangeLogger httpExchangeLogger;
    private final TokenCounter tokenCounter;
    private final ContextCompressionService contextCompressionService;
    private final ContextStrategyService contextStrategyService;
    private final FactsMemoryService factsMemoryService;
    private final ContextAssembler contextAssembler;
    private final MemoryManager memoryManager;
    private final TaskStateService taskStateService;
    private final TaskStateUpdaterService taskStateUpdaterService;
    private final TaskTransitionService taskTransitionService;
    private final InvariantGuard invariantGuard;
    private final InvariantsService invariantsService;
    private final PersonalizationService personalizationService;
    private final double temperature;
    private final int maxTokens;

    public ChatAgent(
            OpenRouterHttpClient openRouterHttpClient,
            AgentChatCompletionService agentChatCompletionService,
            ConversationStore conversationStore,
            HttpExchangeLogger httpExchangeLogger,
            TokenCounter tokenCounter,
            ContextCompressionService contextCompressionService,
            ContextStrategyService contextStrategyService,
            FactsMemoryService factsMemoryService,
            ContextAssembler contextAssembler,
            MemoryManager memoryManager,
            TaskStateService taskStateService,
            TaskStateUpdaterService taskStateUpdaterService,
            TaskTransitionService taskTransitionService,
            InvariantGuard invariantGuard,
            InvariantsService invariantsService,
            PersonalizationService personalizationService,
            @Value("${app.agent.temperature}") double temperature,
            @Value("${app.agent.max-tokens}") int maxTokens) {
        this.openRouterHttpClient = openRouterHttpClient;
        this.agentChatCompletionService = agentChatCompletionService;
        this.conversationStore = conversationStore;
        this.httpExchangeLogger = httpExchangeLogger;
        this.tokenCounter = tokenCounter;
        this.contextCompressionService = contextCompressionService;
        this.contextStrategyService = contextStrategyService;
        this.factsMemoryService = factsMemoryService;
        this.contextAssembler = contextAssembler;
        this.memoryManager = memoryManager;
        this.taskStateService = taskStateService;
        this.taskStateUpdaterService = taskStateUpdaterService;
        this.taskTransitionService = taskTransitionService;
        this.invariantGuard = invariantGuard;
        this.invariantsService = invariantsService;
        this.personalizationService = personalizationService;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    public AgentResponse run(AgentRequest request, String userId) {
        String prompt = request.prompt();
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Промпт не может быть пустым.");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId обязателен.");
        }

        ContextStrategy strategy = INTERNAL_STRATEGY;
        boolean useDay10Strategy = true;
        boolean compressionEnabled = false;

        String sessionId = resolveSessionId(request.sessionId(), strategy, userId);
        ensureSessionOwnership(sessionId, userId);
        contextStrategyService.ensureStrategy(sessionId, strategy);

        String activeSessionId = sessionId;

        taskStateService.promotePendingState(activeSessionId);

        List<String> taskStateLogs = new ArrayList<>();
        TaskStateService.PauseResumeCommand command = taskStateService.detectCommand(prompt);
        if (command == TaskStateService.PauseResumeCommand.PAUSE) {
            if (taskStateService.getState(activeSessionId).isPresent()) {
                appendTransitionLog(taskStateLogs, taskStateService.pause(activeSessionId));
            } else {
                taskStateLogs.add("TASK → пауза: задача ещё не начата");
            }
        } else if (command == TaskStateService.PauseResumeCommand.RESUME) {
            if (taskStateService.getState(activeSessionId).isPresent()) {
                appendTransitionLog(taskStateLogs, taskStateService.resume(activeSessionId));
            } else {
                taskStateLogs.add("TASK → продолжение: задача ещё не начата");
            }
        }

        taskStateService.bootstrapPlanningIfNeeded(activeSessionId, prompt)
                .ifPresent(result -> appendTransitionLog(taskStateLogs, result, "TASK → задача начата"));

        int priorMessages = conversationStore.getStoredMessageCount(activeSessionId);
        Optional<TransitionResult> skipAudit = taskTransitionService.auditUserSkipAttempt(activeSessionId, prompt);
        skipAudit.ifPresent(result -> appendTransitionLog(taskStateLogs, result));

        if (skipAudit.isEmpty()) {
            taskStateService.advancePlanningSubPhase(activeSessionId, prompt, priorMessages)
                    .ifPresent(result -> appendTransitionLog(taskStateLogs, result, "TASK → переход к согласованию плана"));

            taskStateService.confirmExecutionIfReady(activeSessionId, prompt)
                    .ifPresent(result -> appendTransitionLog(taskStateLogs, result, "TASK → переход к разбору тем"));

            taskStateService.startValidationIfReady(activeSessionId, prompt)
                    .ifPresent(result -> appendTransitionLog(taskStateLogs, result, "TASK → переход к самопроверке"));
        }

        taskStateService.advanceValidationAfterMcqAnswer(activeSessionId, prompt)
                .ifPresent(result -> appendTransitionLog(taskStateLogs, result, "TASK → следующий вопрос самопроверки"));

        taskStateService.autoAdvanceToValidationIfReady(activeSessionId)
                .ifPresent(result -> appendTransitionLog(taskStateLogs, result, "TASK → авто: самопроверка"));

        taskStateService.autoAdvanceToDoneIfReady(activeSessionId)
                .ifPresent(result -> appendTransitionLog(taskStateLogs, result, "TASK → авто: тема пройдена"));

        UserProfile profile = personalizationService.getProfile(userId);
        Optional<TaskState> taskStateForGuard = taskStateService.getState(activeSessionId);
        InvariantContext invariantContext = new InvariantContext(
                userId,
                activeSessionId,
                prompt,
                taskStateForGuard,
                profile);
        InvariantCheckResult invariantCheck = invariantGuard.check(invariantContext);

        if (invariantCheck.hardBlock()) {
            return buildInvariantRefusalResponse(
                    sessionId,
                    userId,
                    activeSessionId,
                    prompt,
                    strategy,
                    invariantContext,
                    invariantCheck,
                    taskStateLogs);
        }

        ContextAssembler.AssembledContext assembled =
                contextAssembler.assemble(
                        userId, activeSessionId, prompt, strategy, useDay10Strategy, invariantCheck);
        List<OpenRouterHttpClient.ChatMessage> messages = assembled.messages();
        MemoryContextSnapshot memorySnapshot = assembled.memorySnapshot();

        int currentPromptTokens = tokenCounter.estimateTextTokens(prompt);
        int historyTokens = estimateHistoryTokens(assembled);
        int requestTokensEstimate = tokenCounter.estimateMessagesTokens(messages);
        int contextLimit = tokenCounter.contextWindow();
        int messagesInContext = assembled.messagesInContext()
                + (assembled.factsBlock() != null ? 1 : 0)
                + (assembled.summary() != null && !assembled.summary().isBlank() ? 1 : 0)
                + (memorySnapshot.longTermInContext() != null && !memorySnapshot.longTermInContext().isEmpty() ? 1 : 0)
                + (assembled.taskBlock() != null ? 1 : 0)
                + (assembled.invariantsBlock() != null ? 1 : 0);

        List<String> logs = new ArrayList<>(assembled.memoryLogs());
        logs.addAll(assembled.personalizationLogs());
        logs.addAll(taskStateLogs);
        logs.addAll(assembled.taskStateLogs());
        logs.addAll(assembled.invariantLogs());
        logs.add("Память: PROFILE + TASK + INVARIANTS + LONG + WORKING + SHORT (окно)");
        logs.add("Окно: " + contextStrategyService.windowSize() + " сообщений");
        int stored = conversationStore.getStoredMessageCount(activeSessionId);
        int dropped = Math.max(0, stored - assembled.messagesInContext());
        if (dropped > 0) {
            logs.add("Отброшено из контекста: " + dropped + " сообщений");
        }
        logs.add("Фактов в WORKING: " + memorySnapshot.workingFactsInContext().size());
        logs.add("Токены — текущий запрос: ~" + currentPromptTokens);
        logs.add("Токены — история диалога: ~" + historyTokens + " (" + messagesInContext + " блоков)");
        logs.add("Токены — весь промпт (system + история + запрос): ~" + requestTokensEstimate
                + " / лимит " + contextLimit);

        httpExchangeLogger.logAgentContext(activeSessionId, assembled.messagesInContext(), prompt);
        httpExchangeLogger.logTokenEstimate(currentPromptTokens, historyTokens, requestTokensEstimate, contextLimit);

        boolean estimatedOverflow = tokenCounter.exceedsContextWindow(requestTokensEstimate);
        if (estimatedOverflow) {
            logs.add("Оценка промпта превышает окно модели — запрос всё равно уходит в OpenRouter.");
        }

        AgentChatCompletionService.AgentChatResult completion =
                agentChatCompletionService.complete(messages, temperature, maxTokens);
        String answer = completion.content();
        List<McpToolCallLogDto> mcpToolCalls = completion.mcpToolCalls();

        if (!mcpToolCalls.isEmpty()) {
            logs.add("MCP → выполнено tool calls: " + mcpToolCalls.size());
            for (McpToolCallLogDto call : mcpToolCalls) {
                logs.add("MCP tool: " + call.toolName() + " (" + call.durationMs() + " ms)");
            }
        }

        int promptTokensActual = completion.promptTokens() > 0
                ? completion.promptTokens()
                : requestTokensEstimate;
        int responseTokens = completion.completionTokens() > 0
                ? completion.completionTokens()
                : tokenCounter.estimateTextTokens(answer);
        int totalTokensActual = completion.totalTokens() > 0
                ? completion.totalTokens()
                : promptTokensActual + responseTokens;

        conversationStore.append(activeSessionId, "user", prompt);
        List<String> memoryLogs = new ArrayList<>();
        memoryLogs.add("SHORT → user: сохранено сообщение");

        Map<String, String> updatedFacts = factsMemoryService.updateFacts(activeSessionId, prompt);
        memoryManager.syncWorkingFacts(activeSessionId, updatedFacts);
        memoryLogs.add("WORKING → facts: обновлено " + updatedFacts.size() + " записей (LLM)");

        contextStrategyService.afterUserMessage(activeSessionId, strategy, prompt);

        List<AgentChatMessage> recent = conversationStore.getStoredMessages(activeSessionId);
        List<String> longTermLogs = memoryManager.extractAndStoreLongTerm(
                userId, activeSessionId, prompt, recent);
        memoryLogs.addAll(longTermLogs);

        conversationStore.append(activeSessionId, "assistant", answer);
        memoryLogs.add("SHORT → assistant: сохранено сообщение");

        if (command == TaskStateService.PauseResumeCommand.NONE) {
            taskStateUpdaterService.updateFromTurn(
                    activeSessionId,
                    prompt,
                    answer,
                    updatedFacts,
                    recent).ifPresent(updated -> {
                taskStateLogs.add("TASK → состояние для следующего хода обновлено (LLM)");
                for (TransitionResult transitionResult : updated.transitionResults()) {
                    appendTransitionLog(taskStateLogs, transitionResult);
                }
                taskStateLogs.addAll(taskStateService.buildTaskStateLogs(updated.state(), false));
            });
        }

        contextStrategyService.afterAssistantMessage(activeSessionId, strategy);
        conversationStore.addTokenUsage(activeSessionId, promptTokensActual, responseTokens);

        ConversationStore.SessionTokenTotals sessionTotals = conversationStore.getTokenTotals(activeSessionId);
        double requestCostUsd = tokenCounter.calculateCostUsd(promptTokensActual, responseTokens);
        double sessionCostUsd = tokenCounter.calculateCostUsd(
                sessionTotals.promptTokens(),
                sessionTotals.completionTokens());
        int contextRemaining = Math.max(0, contextLimit - promptTokensActual);
        boolean nearContextLimit = promptTokensActual >= contextLimit * NEAR_LIMIT_RATIO;
        boolean contextOverflow = estimatedOverflow || promptTokensActual > contextLimit;

        logs.add("Токены — ответ модели: " + responseTokens + " (факт из API)");
        logs.add("Токены — prompt факт: " + promptTokensActual + ", total: " + totalTokensActual);
        logs.add(String.format(
                "Стоимость запроса: $%.6f, за сессию: $%.6f",
                requestCostUsd,
                sessionCostUsd));

        httpExchangeLogger.logTokenUsage(promptTokensActual, responseTokens, totalTokensActual, requestCostUsd);

        Map<String, String> factsForStats = updatedFacts;
        int factsTokens = factsMemoryService.estimateFactsTokens(factsForStats);

        MemoryContextSnapshot finalSnapshot = memoryManager.buildContextSnapshot(userId, activeSessionId, strategy);

        TaskStateSnapshot responseTaskSnapshot = assembled.taskStateSnapshot() != null
                && assembled.taskStateSnapshot().active()
                ? assembled.taskStateSnapshot()
                : taskStateService.toSnapshot(
                        taskStateService.getState(activeSessionId).orElse(null),
                        assembled.taskBlock() != null);

        TokenStats tokenStats = new TokenStats(
                currentPromptTokens,
                historyTokens,
                requestTokensEstimate,
                promptTokensActual,
                responseTokens,
                totalTokensActual,
                sessionTotals.promptTokens(),
                sessionTotals.completionTokens(),
                sessionTotals.totalTokens(),
                requestCostUsd,
                sessionCostUsd,
                contextLimit,
                contextRemaining,
                nearContextLimit,
                contextOverflow,
                false,
                false,
                0,
                messagesInContext,
                0,
                null,
                strategy.name(),
                factsTokens,
                factsForStats.size(),
                contextStrategyService.windowSize(),
                conversationStore.getStoredMessageCount(activeSessionId));

        return new AgentResponse(
                answer,
                sessionId,
                List.copyOf(logs),
                tokenStats,
                finalSnapshot,
                List.copyOf(memoryLogs),
                assembled.profileSnapshot(),
                List.copyOf(assembled.personalizationLogs()),
                responseTaskSnapshot,
                List.copyOf(taskStateLogs),
                assembled.invariantsSnapshot(),
                List.copyOf(assembled.invariantLogs()),
                List.copyOf(mcpToolCalls));
    }

    private void appendTransitionLog(List<String> logs, TransitionResult result) {
        appendTransitionLog(logs, result, null);
    }

    private void appendTransitionLog(List<String> logs, TransitionResult result, String acceptedPrefix) {
        if (result == null) {
            return;
        }
        if (result.accepted() && acceptedPrefix != null) {
            logs.add(acceptedPrefix);
        }
        logs.add(result.toLogLine());
        if (result.newState() != null) {
            logs.addAll(taskStateService.buildTaskStateLogs(result.newState(), result.accepted() && acceptedPrefix != null));
        }
    }

    private AgentResponse buildInvariantRefusalResponse(
            String sessionId,
            String userId,
            String activeSessionId,
            String prompt,
            ContextStrategy strategy,
            InvariantContext invariantContext,
            InvariantCheckResult invariantCheck,
            List<String> taskStateLogs) {
        String refusal = invariantGuard.formatRefusal(invariantCheck.hardBlocked());
        List<String> invariantLogs = invariantsService.buildInvariantsLogs(
                invariantContext, false, invariantCheck);
        InvariantsSnapshot invariantsSnapshot = invariantsService.toSnapshot(
                invariantContext, false, invariantCheck);

        conversationStore.append(activeSessionId, "user", prompt);
        conversationStore.append(activeSessionId, "assistant", refusal);
        contextStrategyService.afterUserMessage(activeSessionId, strategy, prompt);
        contextStrategyService.afterAssistantMessage(activeSessionId, strategy);

        MemoryContextSnapshot memorySnapshot = memoryManager.buildContextSnapshot(
                userId, activeSessionId, strategy);
        TaskStateSnapshot taskSnapshot = taskStateService.toSnapshot(
                taskStateService.getState(activeSessionId).orElse(null),
                taskStateService.getState(activeSessionId).isPresent());

        List<String> logs = new ArrayList<>();
        logs.add("INVARIANTS: запрос заблокирован до вызова LLM");
        logs.addAll(invariantLogs);
        logs.addAll(taskStateLogs);

        int promptTokens = tokenCounter.estimateTextTokens(prompt);
        int responseTokens = tokenCounter.estimateTextTokens(refusal);
        TokenStats tokenStats = new TokenStats(
                promptTokens,
                0,
                promptTokens + responseTokens,
                0,
                responseTokens,
                promptTokens + responseTokens,
                0,
                0,
                0,
                0.0,
                0.0,
                tokenCounter.contextWindow(),
                tokenCounter.contextWindow(),
                false,
                false,
                false,
                false,
                0,
                0,
                0,
                null,
                strategy.name(),
                0,
                0,
                contextStrategyService.windowSize(),
                conversationStore.getStoredMessageCount(activeSessionId));

        return new AgentResponse(
                refusal,
                sessionId,
                List.copyOf(logs),
                tokenStats,
                memorySnapshot,
                List.of("SHORT → user: сохранено сообщение", "SHORT → assistant: отказ по инварианту"),
                new UserProfileSnapshot(
                        invariantContext.profile().displayName(),
                        invariantContext.profile().responseStyle(),
                        invariantContext.profile().responseFormat(),
                        invariantContext.profile().constraints(),
                        personalizationService.formatProfileBlock(invariantContext.profile()) != null),
                List.of(),
                taskSnapshot,
                List.copyOf(taskStateLogs),
                invariantsSnapshot,
                List.copyOf(invariantLogs),
                List.of());
    }

    public void resetSession(String sessionId, String userId) {
        ensureSessionOwnership(sessionId, userId);
        conversationStore.clear(sessionId);
    }

    private String resolveSessionId(String sessionId, ContextStrategy strategy, String userId) {
        if (sessionId == null || sessionId.isBlank() || !conversationStore.hasSession(sessionId)) {
            return conversationStore.createSession(userId, strategy);
        }
        return sessionId;
    }

    private void ensureSessionOwnership(String sessionId, String userId) {
        if (!conversationStore.belongsToUser(sessionId, userId)) {
            throw new IllegalArgumentException("Сессия не принадлежит пользователю.");
        }
    }

    private int estimateHistoryTokens(ContextAssembler.AssembledContext assembled) {
        int total = 0;
        if (assembled.profileBlock() != null && !assembled.profileBlock().isBlank()) {
            total += tokenCounter.estimateMessageTokens("system", assembled.profileBlock());
        }
        if (assembled.taskBlock() != null && !assembled.taskBlock().isBlank()) {
            total += tokenCounter.estimateMessageTokens("system", assembled.taskBlock());
        }
        if (assembled.invariantsBlock() != null && !assembled.invariantsBlock().isBlank()) {
            total += tokenCounter.estimateMessageTokens("system", assembled.invariantsBlock());
        }
        if (assembled.summary() != null && !assembled.summary().isBlank()) {
            total += contextCompressionService.estimateSummaryTokens(assembled.summary());
        }
        if (assembled.factsBlock() != null && !assembled.factsBlock().isBlank()) {
            total += tokenCounter.estimateMessageTokens("system", assembled.factsBlock());
        }
        String longTermBlock = memoryManager.formatLongTermBlock(assembled.memorySnapshot().longTermInContext());
        if (longTermBlock != null) {
            total += tokenCounter.estimateMessageTokens("system", longTermBlock);
        }
        List<OpenRouterHttpClient.ChatMessage> historyMessages = assembled.memorySnapshot().shortTermInContext().stream()
                .map(entry -> new OpenRouterHttpClient.ChatMessage(entry.role(), entry.content()))
                .toList();
        total += tokenCounter.estimateHistoryTokens(historyMessages);
        return total;
    }
}

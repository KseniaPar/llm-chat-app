package com.example.llmchat.agent;

import com.example.llmchat.dto.AgentChatMessage;
import com.example.llmchat.dto.AgentRequest;
import com.example.llmchat.dto.AgentResponse;
import com.example.llmchat.dto.MemoryContextSnapshot;
import com.example.llmchat.dto.TokenStats;
import com.example.llmchat.memory.ContextAssembler;
import com.example.llmchat.memory.MemoryManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ChatAgent {

    private static final double NEAR_LIMIT_RATIO = 0.85;
    private static final ContextStrategy INTERNAL_STRATEGY = ContextStrategy.SLIDING_WINDOW;

    private final OpenRouterHttpClient openRouterHttpClient;
    private final ConversationStore conversationStore;
    private final HttpExchangeLogger httpExchangeLogger;
    private final TokenCounter tokenCounter;
    private final ContextCompressionService contextCompressionService;
    private final ContextStrategyService contextStrategyService;
    private final FactsMemoryService factsMemoryService;
    private final ContextAssembler contextAssembler;
    private final MemoryManager memoryManager;
    private final String model;
    private final double temperature;
    private final int maxTokens;

    public ChatAgent(
            OpenRouterHttpClient openRouterHttpClient,
            ConversationStore conversationStore,
            HttpExchangeLogger httpExchangeLogger,
            TokenCounter tokenCounter,
            ContextCompressionService contextCompressionService,
            ContextStrategyService contextStrategyService,
            FactsMemoryService factsMemoryService,
            ContextAssembler contextAssembler,
            MemoryManager memoryManager,
            @Value("${app.openrouter.model}") String model,
            @Value("${app.agent.temperature}") double temperature,
            @Value("${app.agent.max-tokens}") int maxTokens) {
        this.openRouterHttpClient = openRouterHttpClient;
        this.conversationStore = conversationStore;
        this.httpExchangeLogger = httpExchangeLogger;
        this.tokenCounter = tokenCounter;
        this.contextCompressionService = contextCompressionService;
        this.contextStrategyService = contextStrategyService;
        this.factsMemoryService = factsMemoryService;
        this.contextAssembler = contextAssembler;
        this.memoryManager = memoryManager;
        this.model = model;
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

        ContextAssembler.AssembledContext assembled =
                contextAssembler.assemble(userId, activeSessionId, prompt, strategy, useDay10Strategy);
        List<OpenRouterHttpClient.ChatMessage> messages = assembled.messages();
        MemoryContextSnapshot memorySnapshot = assembled.memorySnapshot();

        int currentPromptTokens = tokenCounter.estimateTextTokens(prompt);
        int historyTokens = estimateHistoryTokens(assembled);
        int requestTokensEstimate = tokenCounter.estimateMessagesTokens(messages);
        int contextLimit = tokenCounter.contextWindow();
        int messagesInContext = assembled.messagesInContext()
                + (assembled.factsBlock() != null ? 1 : 0)
                + (assembled.summary() != null && !assembled.summary().isBlank() ? 1 : 0)
                + (memorySnapshot.longTermInContext() != null && !memorySnapshot.longTermInContext().isEmpty() ? 1 : 0);

        List<String> logs = new ArrayList<>(assembled.memoryLogs());
        logs.add("Память: SHORT (окно) + WORKING (facts) + LONG (профиль)");
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

        CompletionResult completion = openRouterHttpClient.complete(model, temperature, maxTokens, messages);
        String answer = completion.content();

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

        return new AgentResponse(answer, sessionId, List.copyOf(logs), tokenStats, finalSnapshot, List.copyOf(memoryLogs));
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

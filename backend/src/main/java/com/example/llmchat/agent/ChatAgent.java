package com.example.llmchat.agent;

import com.example.llmchat.dto.AgentChatMessage;
import com.example.llmchat.dto.AgentRequest;
import com.example.llmchat.dto.AgentResponse;
import com.example.llmchat.dto.TokenStats;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ChatAgent {

    private static final double NEAR_LIMIT_RATIO = 0.85;

    private final OpenRouterHttpClient openRouterHttpClient;
    private final ConversationStore conversationStore;
    private final HttpExchangeLogger httpExchangeLogger;
    private final TokenCounter tokenCounter;
    private final ContextCompressionService contextCompressionService;
    private final ContextStrategyService contextStrategyService;
    private final FactsMemoryService factsMemoryService;
    private final BranchingService branchingService;
    private final String model;
    private final String systemPrompt;
    private final double temperature;
    private final int maxTokens;
    private final boolean defaultCompressionEnabled;

    public ChatAgent(
            OpenRouterHttpClient openRouterHttpClient,
            ConversationStore conversationStore,
            HttpExchangeLogger httpExchangeLogger,
            TokenCounter tokenCounter,
            ContextCompressionService contextCompressionService,
            ContextStrategyService contextStrategyService,
            FactsMemoryService factsMemoryService,
            BranchingService branchingService,
            @Value("${app.openrouter.model}") String model,
            @Value("${app.agent.system-prompt}") String systemPrompt,
            @Value("${app.agent.temperature}") double temperature,
            @Value("${app.agent.max-tokens}") int maxTokens,
            @Value("${app.agent.compression.enabled}") boolean defaultCompressionEnabled) {
        this.openRouterHttpClient = openRouterHttpClient;
        this.conversationStore = conversationStore;
        this.httpExchangeLogger = httpExchangeLogger;
        this.tokenCounter = tokenCounter;
        this.contextCompressionService = contextCompressionService;
        this.contextStrategyService = contextStrategyService;
        this.factsMemoryService = factsMemoryService;
        this.branchingService = branchingService;
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.defaultCompressionEnabled = defaultCompressionEnabled;
    }

    public AgentResponse run(AgentRequest request) {
        String prompt = request.prompt();
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Промпт не может быть пустым.");
        }

        ContextStrategy strategy = ContextStrategy.fromString(request.contextStrategy());
        boolean useDay10Strategy = strategy != null;

        boolean compressionEnabled = !useDay10Strategy && (request.compressionEnabled() != null
                ? request.compressionEnabled()
                : defaultCompressionEnabled);

        String sessionId = resolveSessionId(request.sessionId(), strategy);
        contextStrategyService.ensureStrategy(sessionId, strategy);

        if (strategy == ContextStrategy.BRANCHING && request.branchId() != null && !request.branchId().isBlank()) {
            branchingService.switchBranch(sessionId, request.branchId());
        }

        String activeSessionId = strategy == ContextStrategy.BRANCHING
                ? branchingService.resolveActiveSessionId(sessionId)
                : sessionId;

        String summary = useDay10Strategy ? null : conversationStore.getSummary(sessionId);
        ContextStrategyService.PreparedContext preparedContext =
                contextStrategyService.prepareContext(activeSessionId, strategy);
        List<AgentChatMessage> history = preparedContext.messages();

        List<OpenRouterHttpClient.ChatMessage> messages = buildMessages(
                prompt, summary, history, preparedContext.factsBlock(), useDay10Strategy);

        int currentPromptTokens = tokenCounter.estimateTextTokens(prompt);
        int historyTokens = estimateHistoryTokens(summary, history, preparedContext.factsBlock(), useDay10Strategy);
        int requestTokensEstimate = tokenCounter.estimateMessagesTokens(messages);
        int contextLimit = tokenCounter.contextWindow();
        int messagesInContext = preparedContext.messagesInContext()
                + (preparedContext.factsBlock() != null ? 1 : 0)
                + (!useDay10Strategy && summary != null && !summary.isBlank() ? 1 : 0);

        List<String> logs = new ArrayList<>();
        if (useDay10Strategy) {
            logs.add("Стратегия контекста: " + strategy.name());
            logs.add("Окно: " + contextStrategyService.windowSize() + " сообщений");
            if (preparedContext.messagesDropped() > 0) {
                logs.add("Отброшено из контекста: " + preparedContext.messagesDropped() + " сообщений");
            }
            if (strategy == ContextStrategy.STICKY_FACTS) {
                Map<String, String> facts = conversationStore.getFacts(activeSessionId);
                logs.add("Фактов в памяти: " + facts.size());
            }
        } else {
            logs.add("Сжатие истории: " + (compressionEnabled ? "включено" : "выключено"));
            if (summary != null && !summary.isBlank()) {
                logs.add("В контексте есть summary (~"
                        + contextCompressionService.estimateSummaryTokens(summary) + " токенов)");
            }
        }
        logs.add("Токены — текущий запрос: ~" + currentPromptTokens);
        logs.add("Токены — история диалога: ~" + historyTokens + " (" + messagesInContext + " блоков)");
        logs.add("Токены — весь промпт (system + история + запрос): ~" + requestTokensEstimate
                + " / лимит " + contextLimit);

        httpExchangeLogger.logAgentContext(activeSessionId, history.size(), prompt);
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
        Map<String, String> updatedFacts = contextStrategyService.afterUserMessage(activeSessionId, strategy, prompt);
        conversationStore.append(activeSessionId, "assistant", answer);
        contextStrategyService.afterAssistantMessage(activeSessionId, strategy);
        conversationStore.addTokenUsage(activeSessionId, promptTokensActual, responseTokens);

        ContextCompressionService.CompressionResult compressionResult =
                ContextCompressionService.CompressionResult.notApplied();
        if (!useDay10Strategy) {
            compressionResult = contextCompressionService.compressIfNeeded(sessionId, compressionEnabled);
            if (compressionResult.applied()) {
                logs.add(String.format(
                        "Сжатие: %d сообщений → summary (~%d токенов)",
                        compressionResult.messagesSummarized(),
                        compressionResult.summaryTokens()));
            }
        }

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

        Map<String, String> factsForStats = updatedFacts.isEmpty()
                ? conversationStore.getFacts(activeSessionId)
                : updatedFacts;
        int factsTokens = factsMemoryService.estimateFactsTokens(factsForStats);

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
                compressionEnabled,
                compressionResult.applied(),
                compressionResult.summaryTokens(),
                messagesInContext,
                compressionResult.messagesSummarized(),
                compressionResult.summaryPreview(),
                strategy != null ? strategy.name() : null,
                factsTokens,
                factsForStats.size(),
                contextStrategyService.windowSize(),
                preparedContext.messagesInStore());

        return new AgentResponse(answer, sessionId, List.copyOf(logs), tokenStats);
    }

    public void resetSession(String sessionId) {
        conversationStore.clear(sessionId);
    }

    private String resolveSessionId(String sessionId, ContextStrategy strategy) {
        if (sessionId == null || sessionId.isBlank() || !conversationStore.hasSession(sessionId)) {
            return conversationStore.createSession(strategy);
        }
        return sessionId;
    }

    private int estimateHistoryTokens(
            String summary,
            List<AgentChatMessage> history,
            String factsBlock,
            boolean useDay10Strategy) {
        int total = 0;
        if (!useDay10Strategy) {
            total += contextCompressionService.estimateSummaryTokens(summary);
        }
        if (factsBlock != null && !factsBlock.isBlank()) {
            total += tokenCounter.estimateMessageTokens("system", factsBlock);
        }
        List<OpenRouterHttpClient.ChatMessage> historyMessages = history.stream()
                .map(entry -> new OpenRouterHttpClient.ChatMessage(entry.role(), entry.content()))
                .toList();
        total += tokenCounter.estimateHistoryTokens(historyMessages);
        return total;
    }

    List<OpenRouterHttpClient.ChatMessage> buildMessages(
            String prompt,
            String summary,
            List<AgentChatMessage> history,
            String factsBlock,
            boolean useDay10Strategy) {
        List<OpenRouterHttpClient.ChatMessage> messages = new ArrayList<>();
        messages.add(new OpenRouterHttpClient.ChatMessage("system", systemPrompt));

        if (!useDay10Strategy) {
            String summaryForContext = contextCompressionService.formatSummaryForContext(summary);
            if (summaryForContext != null) {
                messages.add(new OpenRouterHttpClient.ChatMessage("system", summaryForContext));
            }
        }

        if (factsBlock != null && !factsBlock.isBlank()) {
            messages.add(new OpenRouterHttpClient.ChatMessage("system", factsBlock));
        }

        for (AgentChatMessage entry : history) {
            messages.add(new OpenRouterHttpClient.ChatMessage(entry.role(), entry.content()));
        }
        messages.add(new OpenRouterHttpClient.ChatMessage("user", prompt));
        return messages;
    }
}

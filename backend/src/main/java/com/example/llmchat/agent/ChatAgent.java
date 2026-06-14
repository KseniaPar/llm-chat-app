package com.example.llmchat.agent;

import com.example.llmchat.dto.AgentChatMessage;
import com.example.llmchat.dto.AgentRequest;
import com.example.llmchat.dto.AgentResponse;
import com.example.llmchat.dto.TokenStats;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ChatAgent {

    private static final double NEAR_LIMIT_RATIO = 0.85;

    private final OpenRouterHttpClient openRouterHttpClient;
    private final ConversationStore conversationStore;
    private final HttpExchangeLogger httpExchangeLogger;
    private final TokenCounter tokenCounter;
    private final String model;
    private final String systemPrompt;
    private final double temperature;
    private final int maxTokens;

    public ChatAgent(
            OpenRouterHttpClient openRouterHttpClient,
            ConversationStore conversationStore,
            HttpExchangeLogger httpExchangeLogger,
            TokenCounter tokenCounter,
            @Value("${app.openrouter.model}") String model,
            @Value("${app.agent.system-prompt}") String systemPrompt,
            @Value("${app.agent.temperature}") double temperature,
            @Value("${app.agent.max-tokens}") int maxTokens) {
        this.openRouterHttpClient = openRouterHttpClient;
        this.conversationStore = conversationStore;
        this.httpExchangeLogger = httpExchangeLogger;
        this.tokenCounter = tokenCounter;
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    public AgentResponse run(AgentRequest request) {
        String prompt = request.prompt();
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Промпт не может быть пустым.");
        }

        String sessionId = resolveSessionId(request.sessionId());
        List<AgentChatMessage> history = conversationStore.getHistory(sessionId);
        List<OpenRouterHttpClient.ChatMessage> messages = buildMessages(prompt, history);

        int currentPromptTokens = tokenCounter.estimateTextTokens(prompt);
        int historyTokens = estimateHistoryTokens(history);
        int requestTokensEstimate = tokenCounter.estimateMessagesTokens(messages);
        int contextLimit = tokenCounter.contextWindow();

        List<String> logs = new ArrayList<>();
        logs.add("Токены — текущий запрос: ~" + currentPromptTokens);
        logs.add("Токены — история диалога: ~" + historyTokens + " (" + history.size() + " сообщ.)");
        logs.add("Токены — весь промпт (system + история + запрос): ~" + requestTokensEstimate
                + " / лимит " + contextLimit);

        httpExchangeLogger.logAgentContext(sessionId, history.size(), prompt);
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

        conversationStore.append(sessionId, "user", prompt);
        conversationStore.append(sessionId, "assistant", answer);
        conversationStore.addTokenUsage(sessionId, promptTokensActual, responseTokens);

        ConversationStore.SessionTokenTotals sessionTotals = conversationStore.getTokenTotals(sessionId);
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
                contextOverflow);

        return new AgentResponse(answer, sessionId, List.copyOf(logs), tokenStats);
    }

    public void resetSession(String sessionId) {
        conversationStore.clear(sessionId);
    }

    private String resolveSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || !conversationStore.hasSession(sessionId)) {
            return conversationStore.createSession();
        }
        return sessionId;
    }

    private int estimateHistoryTokens(List<AgentChatMessage> history) {
        List<OpenRouterHttpClient.ChatMessage> historyMessages = history.stream()
                .map(entry -> new OpenRouterHttpClient.ChatMessage(entry.role(), entry.content()))
                .toList();
        return tokenCounter.estimateHistoryTokens(historyMessages);
    }

    List<OpenRouterHttpClient.ChatMessage> buildMessages(String prompt, List<AgentChatMessage> history) {
        List<OpenRouterHttpClient.ChatMessage> messages = new ArrayList<>();
        messages.add(new OpenRouterHttpClient.ChatMessage("system", systemPrompt));
        for (AgentChatMessage entry : history) {
            messages.add(new OpenRouterHttpClient.ChatMessage(entry.role(), entry.content()));
        }
        messages.add(new OpenRouterHttpClient.ChatMessage("user", prompt));
        return messages;
    }
}

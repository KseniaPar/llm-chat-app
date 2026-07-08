package com.example.llmchat.localllm;

import com.example.llmchat.agent.ContextStrategy;
import com.example.llmchat.agent.ContextStrategyService;
import com.example.llmchat.agent.ConversationStore;
import com.example.llmchat.auth.SystemUserBootstrap;
import com.example.llmchat.dto.AgentChatMessage;
import com.example.llmchat.dto.LocalLlmAgentChatResponse;
import com.example.llmchat.dto.LocalLlmAgentHistoryResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class LocalLlmAgentService {

    private static final ContextStrategy STRATEGY = ContextStrategy.SLIDING_WINDOW;

    private final LocalLlmService localLlmService;
    private final OllamaHttpClient ollamaHttpClient;
    private final ConversationStore conversationStore;
    private final ContextStrategyService contextStrategyService;
    private final String systemPrompt;
    private final double temperature;
    private final int maxTokens;
    private final String model;

    public LocalLlmAgentService(
            LocalLlmService localLlmService,
            OllamaHttpClient ollamaHttpClient,
            ConversationStore conversationStore,
            ContextStrategyService contextStrategyService,
            @Value("${app.agent.system-prompt}") String systemPrompt,
            @Value("${app.local-llm.temperature}") double temperature,
            @Value("${app.local-llm.max-tokens}") int maxTokens,
            @Value("${app.local-llm.model}") String model) {
        this.localLlmService = localLlmService;
        this.ollamaHttpClient = ollamaHttpClient;
        this.conversationStore = conversationStore;
        this.contextStrategyService = contextStrategyService;
        this.systemPrompt = systemPrompt;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.model = model;
    }

    public LocalLlmAgentChatResponse chat(String prompt, String sessionId) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }

        var status = localLlmService.checkStatus();
        if (!status.online() || !status.modelAvailable()) {
            throw new IllegalStateException(status.message());
        }

        String activeSessionId = resolveSessionId(sessionId);
        contextStrategyService.ensureStrategy(activeSessionId, STRATEGY);

        List<OllamaHttpClient.ChatMessage> messages = buildMessages(activeSessionId, prompt.trim());
        List<String> logs = new ArrayList<>();
        logs.add("LLM → Ollama (" + model + ")");
        logs.add("Окно: " + contextStrategyService.windowSize() + " сообщений");
        logs.add("Сообщений в контексте: " + Math.max(0, messages.size() - 2));

        long startedAt = System.currentTimeMillis();
        OllamaHttpClient.ChatResult result = ollamaHttpClient.chatMessages(messages, model, temperature, maxTokens);
        long durationMs = System.currentTimeMillis() - startedAt;

        String answer = result.content() != null ? result.content().trim() : "";
        if (answer.isBlank()) {
            throw new IllegalStateException("Локальная модель вернула пустой ответ.");
        }

        conversationStore.append(activeSessionId, "user", prompt.trim());
        contextStrategyService.afterUserMessage(activeSessionId, STRATEGY, prompt.trim());
        conversationStore.append(activeSessionId, "assistant", answer);

        logs.add("Ответ получен за " + durationMs + " ms · " + result.evalCount() + " tokens");

        return new LocalLlmAgentChatResponse(
                answer,
                activeSessionId,
                model,
                durationMs,
                result.evalCount(),
                List.copyOf(logs));
    }

    public LocalLlmAgentHistoryResponse history(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || !conversationStore.hasSession(sessionId)) {
            throw new IllegalArgumentException("Сессия не найдена.");
        }
        ensureLocalSession(sessionId);
        return new LocalLlmAgentHistoryResponse(
                sessionId,
                conversationStore.getFullHistoryForDisplay(sessionId));
    }

    public void reset(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (!conversationStore.hasSession(sessionId)) {
            return;
        }
        ensureLocalSession(sessionId);
        conversationStore.clear(sessionId);
    }

    private List<OllamaHttpClient.ChatMessage> buildMessages(String sessionId, String prompt) {
        List<OllamaHttpClient.ChatMessage> messages = new ArrayList<>();
        messages.add(new OllamaHttpClient.ChatMessage("system", systemPrompt));

        List<AgentChatMessage> history = conversationStore.getWindowForContext(
                sessionId,
                contextStrategyService.windowSize());
        for (AgentChatMessage message : history) {
            if (message == null || message.content() == null || message.content().isBlank()) {
                continue;
            }
            String role = "assistant".equalsIgnoreCase(message.role()) ? "assistant" : "user";
            messages.add(new OllamaHttpClient.ChatMessage(role, message.content()));
        }

        messages.add(new OllamaHttpClient.ChatMessage("user", prompt));
        return messages;
    }

    private String resolveSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || !conversationStore.hasSession(sessionId)) {
            return conversationStore.createSession(SystemUserBootstrap.SYSTEM_USER_ID, STRATEGY);
        }
        ensureLocalSession(sessionId);
        return sessionId;
    }

    private void ensureLocalSession(String sessionId) {
        if (!conversationStore.belongsToUser(sessionId, SystemUserBootstrap.SYSTEM_USER_ID)) {
            throw new IllegalArgumentException("Сессия не принадлежит локальному чату.");
        }
    }
}

package com.example.llmchat.agent;

import com.example.llmchat.dto.AgentChatMessage;
import com.example.llmchat.dto.AgentRequest;
import com.example.llmchat.dto.AgentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ChatAgent {

    private final OpenRouterHttpClient openRouterHttpClient;
    private final ConversationStore conversationStore;
    private final HttpExchangeLogger httpExchangeLogger;
    private final String model;
    private final String systemPrompt;
    private final double temperature;
    private final int maxTokens;

    public ChatAgent(
            OpenRouterHttpClient openRouterHttpClient,
            ConversationStore conversationStore,
            HttpExchangeLogger httpExchangeLogger,
            @Value("${app.openrouter.model}") String model,
            @Value("${app.agent.system-prompt}") String systemPrompt,
            @Value("${app.agent.temperature}") double temperature,
            @Value("${app.agent.max-tokens}") int maxTokens) {
        this.openRouterHttpClient = openRouterHttpClient;
        this.conversationStore = conversationStore;
        this.httpExchangeLogger = httpExchangeLogger;
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

        httpExchangeLogger.logAgentContext(sessionId, history.size(), prompt);

        String answer = openRouterHttpClient.complete(model, temperature, maxTokens, messages);

        conversationStore.append(sessionId, "user", prompt);
        conversationStore.append(sessionId, "assistant", answer);

        return new AgentResponse(answer, sessionId, List.of());
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

    private List<OpenRouterHttpClient.ChatMessage> buildMessages(String prompt, List<AgentChatMessage> history) {
        List<OpenRouterHttpClient.ChatMessage> messages = new ArrayList<>();
        messages.add(new OpenRouterHttpClient.ChatMessage("system", systemPrompt));
        for (AgentChatMessage entry : history) {
            messages.add(new OpenRouterHttpClient.ChatMessage(entry.role(), entry.content()));
        }
        messages.add(new OpenRouterHttpClient.ChatMessage("user", prompt));
        return messages;
    }
}

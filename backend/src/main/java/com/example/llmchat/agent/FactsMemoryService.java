package com.example.llmchat.agent;

import com.example.llmchat.dto.AgentChatMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class FactsMemoryService {

    private static final String FACTS_PREFIX = "Известные факты:\n";

    private final OpenRouterHttpClient openRouterHttpClient;
    private final ConversationStore conversationStore;
    private final TokenCounter tokenCounter;
    private final ObjectMapper objectMapper;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final String updateSystemPrompt;

    public FactsMemoryService(
            OpenRouterHttpClient openRouterHttpClient,
            ConversationStore conversationStore,
            TokenCounter tokenCounter,
            ObjectMapper objectMapper,
            @Value("${app.openrouter.model}") String model,
            @Value("${app.agent.temperature}") double temperature,
            @Value("${app.agent.max-tokens}") int maxTokens,
            @Value("${app.agent.facts.update-system-prompt}") String updateSystemPrompt) {
        this.openRouterHttpClient = openRouterHttpClient;
        this.conversationStore = conversationStore;
        this.tokenCounter = tokenCounter;
        this.objectMapper = objectMapper;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.updateSystemPrompt = updateSystemPrompt;
    }

    public Map<String, String> updateFacts(String sessionId, String userMessage) {
        Map<String, String> existing = new LinkedHashMap<>(conversationStore.getFacts(sessionId));
        List<AgentChatMessage> recent = conversationStore.getStoredMessages(sessionId);
        int from = Math.max(0, recent.size() - 4);

        StringBuilder userContent = new StringBuilder();
        if (!existing.isEmpty()) {
            userContent.append("Текущие факты:\n");
            for (Map.Entry<String, String> entry : existing.entrySet()) {
                userContent.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
            userContent.append("\n");
        }
        if (recent.size() > from) {
            userContent.append("Недавний контекст:\n");
            for (AgentChatMessage message : recent.subList(from, recent.size())) {
                userContent.append(message.role()).append(": ").append(message.content()).append("\n");
            }
            userContent.append("\n");
        }
        userContent.append("Новое сообщение пользователя:\n").append(userMessage);

        List<OpenRouterHttpClient.ChatMessage> request = List.of(
                new OpenRouterHttpClient.ChatMessage("system", updateSystemPrompt),
                new OpenRouterHttpClient.ChatMessage("user", userContent.toString()));

        CompletionResult completion = openRouterHttpClient.complete(model, temperature, maxTokens, request, false);
        Map<String, String> updated = parseFactsJson(completion.content());
        existing.putAll(updated);
        conversationStore.setFacts(sessionId, existing);
        return existing;
    }

    public String formatFactsForContext(Map<String, String> facts) {
        if (facts == null || facts.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder(FACTS_PREFIX);
        for (Map.Entry<String, String> entry : facts.entrySet()) {
            builder.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        return builder.toString().trim();
    }

    public int estimateFactsTokens(Map<String, String> facts) {
        String formatted = formatFactsForContext(facts);
        if (formatted == null) {
            return 0;
        }
        return tokenCounter.estimateMessageTokens("system", formatted);
    }

    private Map<String, String> parseFactsJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
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
            Map<String, String> parsed = objectMapper.readValue(json, new TypeReference<>() {
            });
            if (parsed == null || parsed.isEmpty()) {
                return Map.of();
            }
            Map<String, String> sanitized = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : parsed.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    sanitized.put(entry.getKey(), entry.getValue());
                }
            }
            return sanitized;
        } catch (Exception exception) {
            return Map.of();
        }
    }
}

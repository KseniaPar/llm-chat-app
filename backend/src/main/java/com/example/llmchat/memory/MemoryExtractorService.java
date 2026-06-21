package com.example.llmchat.memory;

import com.example.llmchat.agent.CompletionResult;
import com.example.llmchat.agent.OpenRouterHttpClient;
import com.example.llmchat.dto.AgentChatMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MemoryExtractorService {

    private final OpenRouterHttpClient openRouterHttpClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final String longTermExtractPrompt;

    public MemoryExtractorService(
            OpenRouterHttpClient openRouterHttpClient,
            ObjectMapper objectMapper,
            @Value("${app.openrouter.model}") String model,
            @Value("${app.agent.temperature}") double temperature,
            @Value("${app.agent.max-tokens}") int maxTokens,
            @Value("${app.agent.memory.long-term-extract-prompt}") String longTermExtractPrompt) {
        this.openRouterHttpClient = openRouterHttpClient;
        this.objectMapper = objectMapper;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.longTermExtractPrompt = longTermExtractPrompt;
    }

    public LongTermExtraction extractLongTerm(
            Map<String, Map<String, String>> existingLongTerm,
            List<AgentChatMessage> recentMessages,
            String userMessage) {
        StringBuilder userContent = new StringBuilder();
        if (existingLongTerm != null && !existingLongTerm.isEmpty()) {
            userContent.append("Текущая долговременная память:\n");
            for (Map.Entry<String, Map<String, String>> category : existingLongTerm.entrySet()) {
                userContent.append(category.getKey()).append(":\n");
                for (Map.Entry<String, String> entry : category.getValue().entrySet()) {
                    userContent.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
            }
            userContent.append("\n");
        }
        if (recentMessages != null && !recentMessages.isEmpty()) {
            userContent.append("Недавний контекст:\n");
            for (AgentChatMessage message : recentMessages) {
                userContent.append(message.role()).append(": ").append(message.content()).append("\n");
            }
            userContent.append("\n");
        }
        userContent.append("Новое сообщение пользователя:\n").append(userMessage);

        List<OpenRouterHttpClient.ChatMessage> request = List.of(
                new OpenRouterHttpClient.ChatMessage("system", longTermExtractPrompt),
                new OpenRouterHttpClient.ChatMessage("user", userContent.toString()));

        CompletionResult completion = openRouterHttpClient.complete(model, temperature, maxTokens, request, false);
        return parseLongTermJson(completion.content());
    }

    private LongTermExtraction parseLongTermJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return LongTermExtraction.empty();
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
            Map<String, Map<String, String>> result = new LinkedHashMap<>();
            for (String category : List.of("profile", "knowledge", "decisions")) {
                JsonNode node = root.get(category);
                if (node != null && node.isObject()) {
                    Map<String, String> entries = objectMapper.convertValue(node, new TypeReference<>() {
                    });
                    Map<String, String> sanitized = new LinkedHashMap<>();
                    for (Map.Entry<String, String> entry : entries.entrySet()) {
                        if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isBlank()) {
                            sanitized.put(entry.getKey(), entry.getValue());
                        }
                    }
                    if (!sanitized.isEmpty()) {
                        result.put(category, sanitized);
                    }
                }
            }
            return new LongTermExtraction(result);
        } catch (Exception exception) {
            return LongTermExtraction.empty();
        }
    }

    public record LongTermExtraction(Map<String, Map<String, String>> categories) {
        public static LongTermExtraction empty() {
            return new LongTermExtraction(Map.of());
        }

        public boolean isEmpty() {
            return categories == null || categories.isEmpty();
        }
    }
}

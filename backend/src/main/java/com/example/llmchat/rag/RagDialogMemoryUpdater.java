package com.example.llmchat.rag;

import com.example.llmchat.agent.CompletionResult;
import com.example.llmchat.agent.OpenRouterHttpClient;
import com.example.llmchat.dto.RagChatMessageDto;
import com.example.llmchat.dto.RagDialogMemoryDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class RagDialogMemoryUpdater {

    private final OpenRouterHttpClient openRouterHttpClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final String updatePrompt;

    public RagDialogMemoryUpdater(
            OpenRouterHttpClient openRouterHttpClient,
            ObjectMapper objectMapper,
            @Value("${app.openrouter.model}") String model,
            @Value("${app.agent.temperature}") double temperature,
            @Value("${app.agent.max-tokens}") int maxTokens,
            @Value("${app.rag.dialog-memory-update-prompt}") String updatePrompt) {
        this.openRouterHttpClient = openRouterHttpClient;
        this.objectMapper = objectMapper;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.updatePrompt = updatePrompt;
    }

    public RagDialogMemoryDto updateFromTurn(
            RagDialogMemoryDto current,
            String userMessage,
            String assistantMessage,
            List<RagChatMessageDto> recentMessages) {
        StringBuilder userContent = new StringBuilder();
        userContent.append("Текущая память диалога:\n");
        if (current.dialogGoal() != null && !current.dialogGoal().isBlank()) {
            userContent.append("- dialogGoal: ").append(current.dialogGoal()).append("\n");
        } else {
            userContent.append("- dialogGoal: (не задана)\n");
        }
        userContent.append("- clarifications: ").append(formatList(current.clarifications())).append("\n");
        userContent.append("- fixedTerms: ").append(formatList(current.fixedTerms())).append("\n\n");

        if (recentMessages != null && !recentMessages.isEmpty()) {
            userContent.append("Недавний контекст:\n");
            int from = Math.max(0, recentMessages.size() - 6);
            for (RagChatMessageDto message : recentMessages.subList(from, recentMessages.size())) {
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
        RagDialogMemoryDto parsed = parseMemory(completion.content());
        if (parsed == null) {
            return current != null ? current : RagDialogMemoryDto.empty();
        }
        return merge(current, parsed);
    }

    private RagDialogMemoryDto merge(RagDialogMemoryDto current, RagDialogMemoryDto parsed) {
        String goal = parsed.dialogGoal();
        if (goal == null || goal.isBlank()) {
            goal = current != null ? current.dialogGoal() : null;
        }
        List<String> clarifications = mergeLists(
                current != null ? current.clarifications() : List.of(),
                parsed.clarifications());
        List<String> fixedTerms = mergeLists(
                current != null ? current.fixedTerms() : List.of(),
                parsed.fixedTerms());
        return new RagDialogMemoryDto(goal, clarifications, fixedTerms);
    }

    private static List<String> mergeLists(List<String> existing, List<String> incoming) {
        Set<String> merged = new LinkedHashSet<>();
        if (existing != null) {
            for (String item : existing) {
                if (item != null && !item.isBlank()) {
                    merged.add(item.trim());
                }
            }
        }
        if (incoming != null) {
            for (String item : incoming) {
                if (item != null && !item.isBlank()) {
                    merged.add(item.trim());
                }
            }
        }
        return List.copyOf(merged);
    }

    private static String formatList(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "[]";
        }
        return items.toString();
    }

    private RagDialogMemoryDto parseMemory(String raw) {
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
            String dialogGoal = root.path("dialogGoal").asText(null);
            if (dialogGoal != null && dialogGoal.isBlank()) {
                dialogGoal = null;
            }
            return new RagDialogMemoryDto(
                    dialogGoal,
                    readStringList(root.path("clarifications")),
                    readStringList(root.path("fixedTerms")));
        } catch (Exception exception) {
            return null;
        }
    }

    private static List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            String text = item.asText(null);
            if (text != null && !text.isBlank()) {
                result.add(text.trim());
            }
        }
        return result;
    }
}

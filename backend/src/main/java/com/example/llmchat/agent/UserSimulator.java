package com.example.llmchat.agent;

import com.example.llmchat.dto.AgentChatMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class UserSimulator {

    private final OpenRouterHttpClient openRouterHttpClient;
    private final String model;
    private final String systemPrompt;
    private final double temperature;
    private final int maxTokens;
    private final String stopMarker;

    public UserSimulator(
            OpenRouterHttpClient openRouterHttpClient,
            @Value("${app.simulator.model}") String model,
            @Value("${app.simulator.system-prompt}") String systemPrompt,
            @Value("${app.simulator.temperature}") double temperature,
            @Value("${app.simulator.max-tokens}") int maxTokens,
            @Value("${app.simulator.stop-marker}") String stopMarker) {
        this.openRouterHttpClient = openRouterHttpClient;
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.stopMarker = stopMarker;
    }

    public SimulatorMessage generateNextMessage(List<AgentChatMessage> history, String goal) {
        List<OpenRouterHttpClient.ChatMessage> messages = buildMessages(history, goal);
        String raw = openRouterHttpClient.complete(model, temperature, maxTokens, messages, false);
        return parseResponse(raw);
    }

    private List<OpenRouterHttpClient.ChatMessage> buildMessages(List<AgentChatMessage> history, String goal) {
        List<OpenRouterHttpClient.ChatMessage> messages = new ArrayList<>();
        messages.add(new OpenRouterHttpClient.ChatMessage("system", buildSystemPrompt(goal)));

        for (AgentChatMessage entry : history) {
            messages.add(new OpenRouterHttpClient.ChatMessage(entry.role(), entry.content()));
        }

        messages.add(new OpenRouterHttpClient.ChatMessage("user", buildInstruction(history)));

        return messages;
    }

    private String buildSystemPrompt(String goal) {
        if (goal == null || goal.isBlank()) {
            return systemPrompt;
        }
        return systemPrompt + "\n\nЦель диалога: " + goal.trim();
    }

    private String buildInstruction(List<AgentChatMessage> history) {
        if (history.isEmpty()) {
            return """
                    Начни сценарий. Напиши только первое сообщение пользователя:
                    представься вымышленным именем, назови задачу и 1–2 конкретные детали о себе.
                    Не спрашивай у агента, чем он может помочь или что умеет.
                    Не пиши метки этапов в скобках.
                    Без пояснений — только текст сообщения.""";
        }

        String userMessages = history.stream()
                .filter(entry -> "user".equals(entry.role()))
                .map(AgentChatMessage::content)
                .collect(Collectors.joining(" | "));

        int userTurns = (int) history.stream().filter(entry -> "user".equals(entry.role())).count();

        return """
                Прочитай историю выше. Напиши только следующее сообщение пользователя.
                Уже сказано тобой: %s
                Номер твоего сообщения: %d
                Не повторяй дословно уже заданные вопросы.
                Не спрашивай про возможности агента или «чем можешь помочь».
                Не пиши метки этапов в скобках — только живое сообщение пользователя.
                Чередуй советы по своей задаче и проверку памяти агента:
                - первые сообщения: представься, добавь детали, попроси совет;
                - дальше: проверяй память — имя, задачу, детали, пересказ, что было несколько сообщений назад;
                - каждая новая проверка памяти должна касаться другого факта из истории.
                Не завершай диалог — всегда пиши следующее содержательное сообщение.
                """.formatted(userMessages, userTurns + 1);
    }

    private SimulatorMessage parseResponse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new SimulatorMessage("", true);
        }

        String trimmed = raw.trim();
        boolean finished = trimmed.contains(stopMarker);
        String content = cleanContent(trimmed.replace(stopMarker, "").trim());

        if (finished && content.isEmpty()) {
            return new SimulatorMessage("", true);
        }

        return new SimulatorMessage(content, finished);
    }

    private String cleanContent(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }

        return content
                .replaceAll("(?i)\\(проверяю память\\)", "")
                .replaceAll("(?i)\\(развиваю задачу\\)", "")
                .replaceAll("(?m)^\\s*\\([^\\n)]*\\)\\s*", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }
}

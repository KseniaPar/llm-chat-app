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
        String raw = openRouterHttpClient.complete(model, temperature, maxTokens, messages, false).content();
        return parseResponse(raw);
    }

    private List<OpenRouterHttpClient.ChatMessage> buildMessages(List<AgentChatMessage> history, String goal) {
        List<OpenRouterHttpClient.ChatMessage> messages = new ArrayList<>();
        messages.add(new OpenRouterHttpClient.ChatMessage("system", buildSystemPrompt(goal)));

        String userPrompt = history.isEmpty()
                ? buildInstruction(history)
                : formatTranscript(history) + "\n\n" + buildInstruction(history);
        messages.add(new OpenRouterHttpClient.ChatMessage("user", userPrompt));

        return messages;
    }

    private String formatTranscript(List<AgentChatMessage> history) {
        StringBuilder transcript = new StringBuilder("""
                Переписка. Ты — пользователь (сообщения «Ты»). Агент — собеседник, не ты.
                """);
        for (AgentChatMessage entry : history) {
            if ("user".equals(entry.role())) {
                transcript.append("\nТы: ").append(entry.content());
            } else {
                transcript.append("\nАгент: ").append(entry.content());
            }
        }
        return transcript.toString().trim();
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
                    Напиши только первое сообщение пользователя (от первого лица):
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
        int nextTurn = userTurns + 1;

        return """
                Напиши только следующую реплику пользователя («Ты» в переписке выше).
                Уже сказано тобой: %s
                Номер твоего сообщения: %d
                %s
                Обязательно:
                — только от первого лица («я», «мне», «мой»), ты — пользователь, не агент;
                — не давай советов и не обращайся к себе по имени на «ты»;
                — не начинай с «Конечно», «Рекомендую», «Можно попробовать» — это стиль агента;
                — не повторяй дословно уже сказанное;
                — не спрашивай про возможности агента;
                — без меток в скобках;
                — не завершай диалог.
                """.formatted(userMessages, nextTurn, turnInstruction(nextTurn));
    }

    private String turnInstruction(int turn) {
        if (turn == 2) {
            return """
                    Задача этого сообщения: попроси конкретный совет по своей задаче.
                    Память пока не проверяй.""";
        }
        if (turn == 3) {
            return """
                    Задача этого сообщения: невзначай проверить, помнит ли агент контекст.
                    Упомяни от первого лица своё имя, задачу или деталь из первого сообщения
                    («кстати, для моего [задача]…», «если учесть, что я [деталь]…»).
                    Запрещено: «как меня зовут?», «что я просил?», «перескажи», «помнишь?»,
                    обращение к себе по имени на «ты».""";
        }
        if (turn % 2 == 0) {
            return """
                    Задача этого сообщения: развить свою задачу — новая деталь или совет.
                    Память не проверяй.""";
        }
        return """
                Задача этого сообщения: снова невзначай проверить память агента.
                Сошлись на другом факте из истории (имя, задача, деталь, тема 2–3 сообщений назад).
                Вплети факт в живую реплику, не спрашивай в лоб «помнишь?» / «перескажи» / «как меня зовут?».""";
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

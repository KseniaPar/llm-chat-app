package com.example.llmchat.agent;

import com.example.llmchat.dto.AgentChatMessage;
import com.example.llmchat.dto.AgentRequest;
import com.example.llmchat.dto.AgentResponse;
import com.example.llmchat.dto.TokenDemoStep;
import com.example.llmchat.dto.TokenScenarioResult;
import com.example.llmchat.dto.TokenScenarioStreamEvent;
import com.example.llmchat.dto.TokenStats;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Service
public class TokenDemoService {

    private static final Logger log = LoggerFactory.getLogger(TokenDemoService.class);

    private static final int SHORT_TURNS = 4;

    private final ChatAgent chatAgent;
    private final ConversationStore conversationStore;
    private final TokenCounter tokenCounter;
    private final OverflowPromptStore overflowPromptStore;
    private final ObjectMapper objectMapper;

    public TokenDemoService(
            ChatAgent chatAgent,
            ConversationStore conversationStore,
            TokenCounter tokenCounter,
            OverflowPromptStore overflowPromptStore,
            ObjectMapper objectMapper) {
        this.chatAgent = chatAgent;
        this.conversationStore = conversationStore;
        this.tokenCounter = tokenCounter;
        this.overflowPromptStore = overflowPromptStore;
        this.objectMapper = objectMapper;
    }

    public TokenScenarioResult runScenario(String scenario) {
        ScenarioCollector collector = new ScenarioCollector();
        runScenarioStreaming(scenario, collector);
        return collector.toResult();
    }

    public void runScenarioStreaming(String scenario, Consumer<TokenScenarioStreamEvent> sink) {
        switch (scenario) {
            case "short" -> streamShortDialog(sink);
            case "long" -> streamLongDialog(sink);
            case "overflow" -> streamOverflowScenario(sink);
            default -> throw new IllegalArgumentException(
                    "Неизвестный сценарий: " + scenario + ". Используйте short, long или overflow.");
        }
    }

    private void streamShortDialog(Consumer<TokenScenarioStreamEvent> sink) {
        int contextLimit = tokenCounter.contextWindow();
        List<String> prompts = List.of(
                "Привет! Меня зовут Алекс. Помоги спланировать поездку в Прагу на выходные.",
                "Сколько это примерно будет стоить?",
                "Какие музеи стоит посетить в первую очередь?",
                "Подведи краткий итог маршрута на два дня.");

        sink.accept(TokenScenarioStreamEvent.start(
                "short",
                "Короткий диалог",
                "",
                contextLimit));

        DialogRunResult result = runRealDialogStreaming(prompts, "short", sink);
        if (result.failed()) {
            emitDialogFailureOutcome(result, sink);
            return;
        }
        if (result.steps().isEmpty()) {
            sink.accept(TokenScenarioStreamEvent.done("", true, null, null));
            return;
        }

        sink.accept(TokenScenarioStreamEvent.done("", false, null, null));
    }

    private void streamLongDialog(Consumer<TokenScenarioStreamEvent> sink) {
        int contextLimit = tokenCounter.contextWindow();

        sink.accept(TokenScenarioStreamEvent.start(
                "long",
                "Длинный диалог",
                "",
                contextLimit));

        log.info("Длинный сценарий: {} реальных вызовов API...", DialogPrompts.LONG_DIALOG_TURNS);
        DialogRunResult result = runRealDialogStreaming(DialogPrompts.LONG_DIALOG, "long", sink);
        if (result.failed()) {
            emitDialogFailureOutcome(result, sink);
            return;
        }
        if (result.steps().isEmpty()) {
            sink.accept(TokenScenarioStreamEvent.done("", true, null, null));
            return;
        }

        sink.accept(TokenScenarioStreamEvent.done("", false, null, null));
    }

    private void streamOverflowScenario(Consumer<TokenScenarioStreamEvent> sink) {
        int contextLimit = tokenCounter.contextWindow();
        int overflowTurns = overflowPromptStore.totalCalls();

        sink.accept(TokenScenarioStreamEvent.start(
                "overflow",
                "Переполнение контекста",
                "",
                contextLimit));

        log.info("Сценарий переполнения: {} вызовов — накопление фрагментов длинного текста", overflowTurns);

        String sessionId = conversationStore.createSession();
        List<TokenDemoStep> steps = new ArrayList<>();
        String liveError = null;
        Integer liveStatus = null;
        boolean failed = false;

        try {
            for (int turn = 1; turn <= overflowTurns; turn++) {
                String prompt = overflowPromptStore.forCall(turn, overflowTurns);
                log.info("Переполнение — вызов {}/{}: {} символов, промпт: \"{}\"",
                        turn, overflowTurns, prompt.length(), LogText.truncate(prompt));

                sink.accept(TokenScenarioStreamEvent.user(turn, prompt));

                try {
                    AgentResponse response = chatAgent.run(new AgentRequest(prompt, sessionId, false));
                    sessionId = response.sessionId();
                    TokenDemoStep step = stepFromResponse(turn, response.tokens());
                    steps.add(step);
                    sink.accept(TokenScenarioStreamEvent.turn(turn, response.response(), step));
                } catch (OpenRouterHttpException exception) {
                    liveError = extractOpenRouterError(exception);
                    liveStatus = exception.statusCode();
                    failed = true;
                    log.info("Переполнение на ходу {}: HTTP {} — {}", turn, liveStatus, liveError);
                    break;
                }
            }

            if (steps.isEmpty() && !failed) {
                failed = true;
            }

            sink.accept(TokenScenarioStreamEvent.done("", failed, liveError, liveStatus));
        } finally {
            conversationStore.clear(sessionId);
        }
    }

    private DialogRunResult runRealDialogStreaming(
            List<String> userPrompts,
            String label,
            Consumer<TokenScenarioStreamEvent> sink) {
        String sessionId = conversationStore.createSession();
        List<TokenDemoStep> steps = new ArrayList<>();

        try {
            for (int turn = 0; turn < userPrompts.size(); turn++) {
                String prompt = userPrompts.get(turn);
                log.info("Сценарий {} — вызов {}/{}: {} символов, промпт: \"{}\"",
                        label, turn + 1, userPrompts.size(), prompt.length(), LogText.truncate(prompt));

                sink.accept(TokenScenarioStreamEvent.user(turn + 1, prompt));

                try {
                    AgentResponse response = chatAgent.run(new AgentRequest(prompt, sessionId, false));
                    sessionId = response.sessionId();
                    TokenDemoStep step = stepFromResponse(turn + 1, response.tokens());
                    steps.add(step);
                    sink.accept(TokenScenarioStreamEvent.turn(turn + 1, response.response(), step));
                } catch (OpenRouterHttpException exception) {
                    String liveError = extractOpenRouterError(exception);
                    int liveStatus = exception.statusCode();
                    log.info("Сценарий {} — ошибка на ходу {}: HTTP {} — {}",
                            label, turn + 1, liveStatus, liveError);
                    return new DialogRunResult(
                            List.copyOf(steps),
                            true,
                            liveError,
                            liveStatus,
                            turn + 1);
                }
            }

            return new DialogRunResult(List.copyOf(steps), false, null, null, 0);
        } finally {
            conversationStore.clear(sessionId);
        }
    }

    private void emitDialogFailureOutcome(
            DialogRunResult result,
            Consumer<TokenScenarioStreamEvent> sink) {
        sink.accept(TokenScenarioStreamEvent.done(
                "",
                true,
                result.liveError(),
                result.liveStatus()));
    }

    private TokenDemoStep stepFromResponse(int turn, TokenStats stats) {
        return new TokenDemoStep(
                turn,
                stats.currentPromptTokens(),
                stats.historyTokens(),
                stats.promptTokensActual(),
                stats.responseTokens(),
                stats.sessionTotalTokens(),
                stats.sessionCostUsd());
    }

    private String extractOpenRouterError(OpenRouterHttpException exception) {
        String message = exception.getMessage();
        if (message == null) {
            return "Неизвестная ошибка OpenRouter";
        }

        int separator = message.indexOf(" — ");
        String body = separator >= 0 ? message.substring(separator + 3) : message;

        try {
            JsonNode root = objectMapper.readTree(body);
            String errorMessage = root.path("error").path("message").asText(null);
            if (errorMessage != null && !errorMessage.isBlank()) {
                return errorMessage;
            }
        } catch (Exception ignored) {
            // use raw body below
        }

        return body.length() > 800 ? body.substring(0, 800) + "..." : body;
    }

    private record DialogRunResult(
            List<TokenDemoStep> steps,
            boolean failed,
            String liveError,
            Integer liveStatus,
            int failedTurn) {
    }

    private static final class ScenarioCollector implements Consumer<TokenScenarioStreamEvent> {

        private String id;
        private String title;
        private String description;
        private int modelContextLimit;
        private String outcome;
        private boolean failed;
        private String liveApiResponse;
        private String liveApiError;
        private Integer liveApiStatusCode;
        private final List<TokenDemoStep> steps = new ArrayList<>();
        private final List<AgentChatMessage> history = new ArrayList<>();

        @Override
        public void accept(TokenScenarioStreamEvent event) {
            switch (event.event()) {
                case "start" -> {
                    id = event.id();
                    title = event.title();
                    description = event.description();
                    modelContextLimit = event.modelContextLimit();
                }
                case "user" -> history.add(new AgentChatMessage("user", event.content()));
                case "turn" -> {
                    history.add(new AgentChatMessage("assistant", event.content()));
                    steps.add(event.step());
                    liveApiResponse = event.content();
                }
                case "done" -> {
                    outcome = event.outcome();
                    failed = Boolean.TRUE.equals(event.failed());
                    liveApiError = event.liveApiError();
                    liveApiStatusCode = event.liveApiStatusCode();
                }
                default -> {
                    // ignore unknown events
                }
            }
        }

        TokenScenarioResult toResult() {
            return new TokenScenarioResult(
                    id,
                    title,
                    description,
                    List.copyOf(steps),
                    List.copyOf(history),
                    outcome,
                    failed,
                    liveApiResponse,
                    liveApiError,
                    liveApiStatusCode,
                    modelContextLimit);
        }
    }
}

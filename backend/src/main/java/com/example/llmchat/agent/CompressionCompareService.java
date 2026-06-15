package com.example.llmchat.agent;

import com.example.llmchat.dto.AgentRequest;
import com.example.llmchat.dto.AgentResponse;
import com.example.llmchat.dto.CompressionCompareResponse;
import com.example.llmchat.dto.CompressionEvent;
import com.example.llmchat.dto.CompressionVariantResult;
import com.example.llmchat.dto.TokenDemoStep;
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
public class CompressionCompareService {

    private static final Logger log = LoggerFactory.getLogger(CompressionCompareService.class);

    private final ChatAgent chatAgent;
    private final ConversationStore conversationStore;
    private final TokenCounter tokenCounter;
    private final ObjectMapper objectMapper;

    public CompressionCompareService(
            ChatAgent chatAgent,
            ConversationStore conversationStore,
            TokenCounter tokenCounter,
            ObjectMapper objectMapper) {
        this.chatAgent = chatAgent;
        this.conversationStore = conversationStore;
        this.tokenCounter = tokenCounter;
        this.objectMapper = objectMapper;
    }

    public void runComparisonStreaming(Consumer<TokenScenarioStreamEvent> sink) {
        int contextLimit = tokenCounter.contextWindow();

        sink.accept(TokenScenarioStreamEvent.compareStart(
                "Сравнение сжатия истории",
                "Один и тот же диалог из 20 ходов — без сжатия и со сжатием",
                contextLimit));

        CompressionVariantResult raw = runVariant("raw", "Без сжатия", false, sink);
        CompressionVariantResult compressed = runVariant("compressed", "Со сжатием", true, sink);

        CompressionCompareResponse compare = buildCompareResponse(raw, compressed);
        sink.accept(TokenScenarioStreamEvent.compareDone(compare));
    }

    private CompressionVariantResult runVariant(
            String mode,
            String title,
            boolean compressionEnabled,
            Consumer<TokenScenarioStreamEvent> sink) {
        int contextLimit = tokenCounter.contextWindow();
        sink.accept(TokenScenarioStreamEvent.variantStart(mode, title, contextLimit));

        String sessionId = conversationStore.createSession();
        List<TokenDemoStep> steps = new ArrayList<>();
        List<CompressionEvent> compressionEvents = new ArrayList<>();
        String probeAnswer = null;
        String liveError = null;
        Integer liveStatus = null;
        boolean failed = false;

        try {
            List<String> prompts = DialogPrompts.LONG_DIALOG;
            for (int turn = 1; turn <= prompts.size(); turn++) {
                String prompt = prompts.get(turn - 1);
                log.info("Сравнение {} — ход {}/{}: \"{}\"", mode, turn, prompts.size(), LogText.truncate(prompt));

                sink.accept(TokenScenarioStreamEvent.user(turn, prompt));

                try {
                    AgentResponse response = chatAgent.run(new AgentRequest(prompt, sessionId, compressionEnabled));
                    sessionId = response.sessionId();
                    TokenStats stats = response.tokens();
                    TokenDemoStep step = stepFromResponse(turn, stats);
                    steps.add(step);

                    if (stats.compressionApplied()) {
                        CompressionEvent event = new CompressionEvent(
                                turn,
                                stats.messagesSummarized(),
                                stats.summaryTokens(),
                                stats.summaryPreview());
                        compressionEvents.add(event);
                        sink.accept(TokenScenarioStreamEvent.compressed(
                                turn,
                                stats.messagesSummarized(),
                                stats.summaryTokens(),
                                stats.summaryPreview()));
                    }

                    if (turn == DialogPrompts.PROBE_TURN) {
                        probeAnswer = response.response();
                    }

                    sink.accept(TokenScenarioStreamEvent.turn(turn, response.response(), step));
                } catch (OpenRouterHttpException exception) {
                    liveError = extractOpenRouterError(exception);
                    liveStatus = exception.statusCode();
                    failed = true;
                    log.info("Сравнение {} — ошибка на ходу {}: HTTP {} — {}", mode, turn, liveStatus, liveError);
                    break;
                }
            }

            TokenDemoStep lastStep = steps.isEmpty() ? null : steps.get(steps.size() - 1);
            CompressionVariantResult result = new CompressionVariantResult(
                    mode,
                    title,
                    List.copyOf(steps),
                    probeAnswer,
                    lastStep != null ? lastStep.historyTokens() : 0,
                    lastStep != null ? lastStep.sessionTotalTokens() : 0,
                    lastStep != null ? lastStep.sessionCostUsd() : 0.0,
                    List.copyOf(compressionEvents),
                    failed,
                    liveError,
                    liveStatus);

            sink.accept(TokenScenarioStreamEvent.variantDone(result));
            return result;
        } finally {
            conversationStore.clear(sessionId);
        }
    }

    private CompressionCompareResponse buildCompareResponse(
            CompressionVariantResult raw,
            CompressionVariantResult compressed) {
        int historySaved = Math.max(0, raw.finalHistoryTokens() - compressed.finalHistoryTokens());
        int sessionSaved = Math.max(0, raw.sessionTotalTokens() - compressed.sessionTotalTokens());
        double costSaved = Math.max(0.0, raw.sessionCostUsd() - compressed.sessionCostUsd());

        double historySavingsPercent = raw.finalHistoryTokens() > 0
                ? (historySaved * 100.0) / raw.finalHistoryTokens()
                : 0.0;
        double sessionSavingsPercent = raw.sessionTotalTokens() > 0
                ? (sessionSaved * 100.0) / raw.sessionTotalTokens()
                : 0.0;

        return new CompressionCompareResponse(
                raw,
                compressed,
                historySaved,
                sessionSaved,
                costSaved,
                historySavingsPercent,
                sessionSavingsPercent,
                tokenCounter.contextWindow(),
                DialogPrompts.PROBE_TURN);
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
}

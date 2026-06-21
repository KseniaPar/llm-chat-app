package com.example.llmchat.agent;

import com.example.llmchat.auth.SystemUserBootstrap;
import com.example.llmchat.dto.AgentRequest;
import com.example.llmchat.dto.AgentResponse;
import com.example.llmchat.dto.BranchInfoDto;
import com.example.llmchat.dto.StrategyCompareResponse;
import com.example.llmchat.dto.StrategyVariantResult;
import com.example.llmchat.dto.TokenDemoStep;
import com.example.llmchat.dto.TokenScenarioStreamEvent;
import com.example.llmchat.dto.TokenStats;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class StrategyCompareService {

    private static final Logger log = LoggerFactory.getLogger(StrategyCompareService.class);

    private final ChatAgent chatAgent;
    private final ConversationStore conversationStore;
    private final BranchingService branchingService;
    private final TokenCounter tokenCounter;
    private final ObjectMapper objectMapper;

    public StrategyCompareService(
            ChatAgent chatAgent,
            ConversationStore conversationStore,
            BranchingService branchingService,
            TokenCounter tokenCounter,
            ObjectMapper objectMapper) {
        this.chatAgent = chatAgent;
        this.conversationStore = conversationStore;
        this.branchingService = branchingService;
        this.tokenCounter = tokenCounter;
        this.objectMapper = objectMapper;
    }

    public void runComparisonStreaming(Consumer<TokenScenarioStreamEvent> sink) {
        int contextLimit = tokenCounter.contextWindow();

        sink.accept(TokenScenarioStreamEvent.strategyCompareStart(
                "Сравнение стратегий контекста",
                "Один сценарий «Сбор ТЗ» — Sliding Window, Sticky Facts, Branching",
                contextLimit));

        List<StrategyVariantResult> variants = new ArrayList<>();
        variants.add(runVariant(ContextStrategy.SLIDING_WINDOW, "Sliding Window", sink));
        variants.add(runVariant(ContextStrategy.STICKY_FACTS, "Sticky Facts", sink));
        variants.add(runBranchingVariant(sink));

        StrategyCompareResponse compare = new StrategyCompareResponse(
                List.copyOf(variants),
                contextLimit,
                DialogPrompts.TZ_PROBE_TURN,
                DialogPrompts.TZ_DIALOG_TURNS);

        sink.accept(TokenScenarioStreamEvent.strategyCompareDone(compare));
    }

    private StrategyVariantResult runVariant(
            ContextStrategy strategy,
            String title,
            Consumer<TokenScenarioStreamEvent> sink) {
        String mode = strategy.name().toLowerCase();
        sink.accept(TokenScenarioStreamEvent.strategyStart(mode, title, tokenCounter.contextWindow()));

        String sessionId = conversationStore.createDemoSession(strategy);
        List<TokenDemoStep> steps = new ArrayList<>();
        List<Map<String, String>> factsSnapshots = new ArrayList<>();
        String probeAnswer = null;
        String finalAnswer = null;
        String liveError = null;
        Integer liveStatus = null;
        boolean failed = false;

        try {
            List<String> prompts = DialogPrompts.TZ_DIALOG;
            for (int turn = 1; turn <= prompts.size(); turn++) {
                String prompt = prompts.get(turn - 1);
                log.info("Стратегия {} — ход {}/{}: \"{}\"", mode, turn, prompts.size(), LogText.truncate(prompt));

                sink.accept(TokenScenarioStreamEvent.user(turn, prompt));

                try {
                    AgentResponse response = chatAgent.run(new AgentRequest(
                            prompt, sessionId, false, strategy.name(), null),
                            SystemUserBootstrap.SYSTEM_USER_ID);
                    sessionId = response.sessionId();
                    TokenStats stats = response.tokens();
                    steps.add(stepFromResponse(turn, stats));

                    if (strategy == ContextStrategy.STICKY_FACTS && stats.factsCount() > 0) {
                        Map<String, String> facts = conversationStore.getFacts(sessionId);
                        factsSnapshots.add(new LinkedHashMap<>(facts));
                        sink.accept(TokenScenarioStreamEvent.factsUpdated(turn, facts));
                    }

                    if (turn == DialogPrompts.TZ_PROBE_TURN) {
                        probeAnswer = response.response();
                    }
                    if (turn == prompts.size()) {
                        finalAnswer = response.response();
                    }

                    sink.accept(TokenScenarioStreamEvent.turn(turn, response.response(), steps.get(steps.size() - 1)));
                } catch (Exception exception) {
                    if (exception instanceof OpenRouterHttpException openRouterException) {
                        liveError = extractOpenRouterError(openRouterException);
                        liveStatus = openRouterException.statusCode();
                    } else {
                        liveError = exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName();
                        liveStatus = 502;
                    }
                    failed = true;
                    log.warn("Стратегия {} — ошибка на ходу {}: {}", mode, turn, liveError);
                    break;
                }
            }

            TokenDemoStep lastStep = steps.isEmpty() ? null : steps.get(steps.size() - 1);
            int factsCount = conversationStore.getFacts(sessionId).size();

            StrategyVariantResult result = new StrategyVariantResult(
                    mode,
                    title,
                    List.copyOf(steps),
                    probeAnswer,
                    finalAnswer,
                    lastStep != null ? lastStep.historyTokens() : 0,
                    lastStep != null ? lastStep.sessionTotalTokens() : 0,
                    lastStep != null ? lastStep.sessionCostUsd() : 0.0,
                    factsCount,
                    lastStep != null ? steps.get(steps.size() - 1).historyTokens() : 0,
                    List.copyOf(factsSnapshots),
                    failed,
                    liveError,
                    liveStatus);

            sink.accept(TokenScenarioStreamEvent.strategyVariantDone(result));
            return result;
        } finally {
            conversationStore.clear(sessionId);
        }
    }

    private StrategyVariantResult runBranchingVariant(Consumer<TokenScenarioStreamEvent> sink) {
        String mode = ContextStrategy.BRANCHING.name().toLowerCase();
        String title = "Branching";
        sink.accept(TokenScenarioStreamEvent.strategyStart(mode, title, tokenCounter.contextWindow()));

        String sessionId = conversationStore.createDemoSession(ContextStrategy.BRANCHING);
        List<TokenDemoStep> steps = new ArrayList<>();
        String probeAnswer = null;
        String finalAnswer = null;
        String liveError = null;
        Integer liveStatus = null;
        boolean failed = false;
        String branchASessionId = null;

        try {
            List<String> sharedPrompts = DialogPrompts.TZ_DIALOG.subList(0, DialogPrompts.TZ_BRANCH_FORK_TURN);
            for (int turn = 1; turn <= sharedPrompts.size(); turn++) {
                String prompt = sharedPrompts.get(turn - 1);
                sink.accept(TokenScenarioStreamEvent.user(turn, prompt));

                try {
                    AgentResponse response = chatAgent.run(new AgentRequest(
                            prompt, sessionId, false, ContextStrategy.BRANCHING.name(), null),
                            SystemUserBootstrap.SYSTEM_USER_ID);
                    sessionId = response.sessionId();
                    steps.add(stepFromResponse(turn, response.tokens()));
                    sink.accept(TokenScenarioStreamEvent.turn(turn, response.response(), steps.get(steps.size() - 1)));
                } catch (Exception exception) {
                    if (exception instanceof OpenRouterHttpException openRouterException) {
                        liveError = extractOpenRouterError(openRouterException);
                        liveStatus = openRouterException.statusCode();
                    } else {
                        liveError = exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName();
                        liveStatus = 502;
                    }
                    failed = true;
                    log.warn("Стратегия {} — ошибка на ходу {}: {}", mode, turn, liveError);
                    break;
                }
            }

            if (!failed) {
                branchingService.createCheckpoint(sessionId);
                List<BranchInfo> branches = branchingService.createBranches(sessionId);
                List<BranchInfoDto> branchDtos = branches.stream()
                        .map(b -> new BranchInfoDto(b.branchId(), b.label(), b.sessionId()))
                        .toList();
                sink.accept(TokenScenarioStreamEvent.branchCreated(DialogPrompts.TZ_BRANCH_FORK_TURN, branchDtos));

                branchASessionId = branches.get(0).sessionId();
                String branchBSessionId = branches.size() > 1 ? branches.get(1).sessionId() : branchASessionId;

                List<String> branchAPrompts = DialogPrompts.TZ_DIALOG.subList(DialogPrompts.TZ_BRANCH_FORK_TURN, DialogPrompts.TZ_DIALOG.size());

                for (int i = 0; i < branchAPrompts.size(); i++) {
                    int turn = DialogPrompts.TZ_BRANCH_FORK_TURN + 1 + i;
                    String promptA = branchAPrompts.get(i);

                    sink.accept(TokenScenarioStreamEvent.user(turn, promptA));

                    try {
                        AgentResponse responseA = chatAgent.run(new AgentRequest(
                                promptA, sessionId, false, ContextStrategy.BRANCHING.name(), branches.get(0).branchId()),
                                SystemUserBootstrap.SYSTEM_USER_ID);

                        steps.add(stepFromResponse(turn, responseA.tokens()));

                        if (turn == DialogPrompts.TZ_PROBE_TURN) {
                            probeAnswer = responseA.response();
                        }
                        if (turn == DialogPrompts.TZ_DIALOG_TURNS) {
                            finalAnswer = responseA.response();
                        }

                        sink.accept(TokenScenarioStreamEvent.turn(turn, responseA.response(), steps.get(steps.size() - 1)));
                    } catch (Exception exception) {
                        if (exception instanceof OpenRouterHttpException openRouterException) {
                            liveError = extractOpenRouterError(openRouterException);
                            liveStatus = openRouterException.statusCode();
                        } else {
                            liveError = exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName();
                            liveStatus = 502;
                        }
                        failed = true;
                        log.warn("Стратегия branching — ошибка на ходу {}: {}", turn, liveError);
                        break;
                    }
                }

                conversationStore.clear(branchBSessionId);
            }

            TokenDemoStep lastStep = steps.isEmpty() ? null : steps.get(steps.size() - 1);
            StrategyVariantResult result = new StrategyVariantResult(
                    mode,
                    title,
                    List.copyOf(steps),
                    probeAnswer,
                    finalAnswer,
                    lastStep != null ? lastStep.historyTokens() : 0,
                    lastStep != null ? lastStep.sessionTotalTokens() : 0,
                    lastStep != null ? lastStep.sessionCostUsd() : 0.0,
                    0,
                    lastStep != null ? lastStep.historyTokens() : 0,
                    List.of(),
                    failed,
                    liveError,
                    liveStatus);

            sink.accept(TokenScenarioStreamEvent.strategyVariantDone(result));
            return result;
        } finally {
            conversationStore.clear(sessionId);
            if (branchASessionId != null) {
                conversationStore.clear(branchASessionId);
            }
        }
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

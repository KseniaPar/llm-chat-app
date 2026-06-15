package com.example.llmchat.controller;

import com.example.llmchat.agent.BranchInfo;
import com.example.llmchat.agent.BranchingService;
import com.example.llmchat.agent.ChatAgent;
import com.example.llmchat.agent.CompressionCompareService;
import com.example.llmchat.agent.ContextStrategy;
import com.example.llmchat.agent.ConversationStore;
import com.example.llmchat.agent.StrategyCompareService;
import com.example.llmchat.agent.TokenDemoService;
import com.example.llmchat.dto.AgentHistoryResponse;
import com.example.llmchat.dto.AgentRequest;
import com.example.llmchat.dto.AgentResetRequest;
import com.example.llmchat.dto.AgentResponse;
import com.example.llmchat.dto.BranchCheckpointRequest;
import com.example.llmchat.dto.BranchCheckpointResponse;
import com.example.llmchat.dto.BranchCreateRequest;
import com.example.llmchat.dto.BranchCreateResponse;
import com.example.llmchat.dto.BranchInfoDto;
import com.example.llmchat.dto.BranchSwitchRequest;
import com.example.llmchat.dto.BranchSwitchResponse;
import com.example.llmchat.dto.TokenScenarioResult;
import com.example.llmchat.dto.TokenScenarioStreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final ChatAgent chatAgent;
    private final ConversationStore conversationStore;
    private final BranchingService branchingService;
    private final TokenDemoService tokenDemoService;
    private final CompressionCompareService compressionCompareService;
    private final StrategyCompareService strategyCompareService;

    public AgentController(
            ChatAgent chatAgent,
            ConversationStore conversationStore,
            BranchingService branchingService,
            TokenDemoService tokenDemoService,
            CompressionCompareService compressionCompareService,
            StrategyCompareService strategyCompareService) {
        this.chatAgent = chatAgent;
        this.conversationStore = conversationStore;
        this.branchingService = branchingService;
        this.tokenDemoService = tokenDemoService;
        this.compressionCompareService = compressionCompareService;
        this.strategyCompareService = strategyCompareService;
    }

    @PostMapping("/chat")
    public AgentResponse chat(@RequestBody AgentRequest request) {
        log.info("POST /api/agent/chat — prompt length: {}, sessionId: {}, strategy: {}",
                request.prompt() != null ? request.prompt().length() : 0,
                request.sessionId(),
                request.contextStrategy());
        return chatAgent.run(request);
    }

    @PostMapping("/reset")
    public void reset(@RequestBody AgentResetRequest request) {
        log.info("POST /api/agent/reset — sessionId: {}", request.sessionId());
        chatAgent.resetSession(request.sessionId());
    }

    @PostMapping("/branch/checkpoint")
    public BranchCheckpointResponse branchCheckpoint(@RequestBody BranchCheckpointRequest request) {
        log.info("POST /api/agent/branch/checkpoint — sessionId: {}", request.sessionId());
        if (request.sessionId() == null || request.sessionId().isBlank()) {
            throw new IllegalArgumentException("sessionId обязателен.");
        }
        conversationStore.setContextStrategy(request.sessionId(), ContextStrategy.BRANCHING);
        int forkIndex = branchingService.createCheckpoint(request.sessionId());
        return new BranchCheckpointResponse(request.sessionId(), forkIndex);
    }

    @PostMapping("/branch/create")
    public BranchCreateResponse branchCreate(@RequestBody BranchCreateRequest request) {
        log.info("POST /api/agent/branch/create — sessionId: {}", request.sessionId());
        if (request.sessionId() == null || request.sessionId().isBlank()) {
            throw new IllegalArgumentException("sessionId обязателен.");
        }
        List<BranchInfo> branches = branchingService.createBranches(request.sessionId());
        List<BranchInfoDto> dtos = branches.stream()
                .map(b -> new BranchInfoDto(b.branchId(), b.label(), b.sessionId()))
                .toList();
        String activeBranchId = conversationStore.getState(request.sessionId()).getActiveBranchId();
        return new BranchCreateResponse(request.sessionId(), dtos, activeBranchId);
    }

    @PostMapping("/branch/switch")
    public BranchSwitchResponse branchSwitch(@RequestBody BranchSwitchRequest request) {
        log.info("POST /api/agent/branch/switch — sessionId: {}, branchId: {}",
                request.sessionId(), request.branchId());
        if (request.sessionId() == null || request.sessionId().isBlank()) {
            throw new IllegalArgumentException("sessionId обязателен.");
        }
        String activeBranchSessionId = branchingService.switchBranch(request.sessionId(), request.branchId());
        return new BranchSwitchResponse(request.sessionId(), request.branchId(), activeBranchSessionId);
    }

    @GetMapping("/token-scenario")
    public TokenScenarioResult tokenScenario(@RequestParam String scenario) {
        log.info("GET /api/agent/token-scenario — сценарий: {}", scenario);
        return tokenDemoService.runScenario(scenario);
    }

    @GetMapping(value = "/compression-compare/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamCompressionCompare() {
        log.info("GET /api/agent/compression-compare/stream");

        SseEmitter emitter = new SseEmitter(900_000L);
        CompletableFuture.runAsync(() -> {
            try {
                compressionCompareService.runComparisonStreaming(event -> sendStreamEvent(emitter, event));
                emitter.complete();
            } catch (Exception exception) {
                log.error("Ошибка стрима сравнения сжатия", exception);
                emitter.completeWithError(exception);
            }
        });

        return emitter;
    }

    @GetMapping(value = "/strategy-compare/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamStrategyCompare() {
        log.info("GET /api/agent/strategy-compare/stream");

        SseEmitter emitter = new SseEmitter(900_000L);
        CompletableFuture.runAsync(() -> {
            try {
                strategyCompareService.runComparisonStreaming(event -> sendStreamEvent(emitter, event));
                emitter.complete();
            } catch (Exception exception) {
                log.error("Ошибка стрима сравнения стратегий", exception);
                emitter.completeWithError(exception);
            }
        });

        return emitter;
    }

    @GetMapping(value = "/token-scenario/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTokenScenario(@RequestParam String scenario) {
        log.info("GET /api/agent/token-scenario/stream — сценарий: {}", scenario);

        SseEmitter emitter = new SseEmitter(900_000L);
        CompletableFuture.runAsync(() -> {
            try {
                tokenDemoService.runScenarioStreaming(scenario, event -> sendStreamEvent(emitter, event));
                emitter.complete();
            } catch (Exception exception) {
                log.error("Ошибка стрима сценария {}", scenario, exception);
                emitter.completeWithError(exception);
            }
        });

        return emitter;
    }

    private void sendStreamEvent(SseEmitter emitter, TokenScenarioStreamEvent event) {
        try {
            emitter.send(SseEmitter.event().data(event));
        } catch (IOException exception) {
            throw new IllegalStateException("Клиент отключился от стрима сценария", exception);
        }
    }

    @GetMapping("/history")
    public AgentHistoryResponse history(@RequestParam String sessionId) {
        log.info("GET /api/agent/history — sessionId: {}", sessionId);
        if (sessionId == null || sessionId.isBlank() || !conversationStore.hasSession(sessionId)) {
            throw new IllegalArgumentException("Сессия не найдена.");
        }

        ContextStrategy strategy = conversationStore.getContextStrategy(sessionId);
        String activeSessionId = strategy == ContextStrategy.BRANCHING
                ? branchingService.resolveActiveSessionId(sessionId)
                : sessionId;

        List<BranchInfoDto> branches = conversationStore.getBranches(sessionId).stream()
                .map(b -> new BranchInfoDto(b.branchId(), b.label(), b.sessionId()))
                .toList();

        return new AgentHistoryResponse(
                sessionId,
                conversationStore.getFullHistoryForDisplay(activeSessionId),
                conversationStore.getSummary(sessionId),
                strategy != null ? strategy.name() : null,
                conversationStore.getFacts(activeSessionId),
                branches,
                conversationStore.getState(sessionId).getActiveBranchId(),
                conversationStore.getForkMessageIndex(sessionId));
    }
}

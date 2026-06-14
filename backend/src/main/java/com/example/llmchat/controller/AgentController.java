package com.example.llmchat.controller;

import com.example.llmchat.agent.ChatAgent;
import com.example.llmchat.agent.ConversationStore;
import com.example.llmchat.agent.TokenDemoService;
import com.example.llmchat.dto.AgentHistoryResponse;
import com.example.llmchat.dto.AgentRequest;
import com.example.llmchat.dto.AgentResetRequest;
import com.example.llmchat.dto.AgentResponse;
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
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final ChatAgent chatAgent;
    private final ConversationStore conversationStore;
    private final TokenDemoService tokenDemoService;

    public AgentController(
            ChatAgent chatAgent,
            ConversationStore conversationStore,
            TokenDemoService tokenDemoService) {
        this.chatAgent = chatAgent;
        this.conversationStore = conversationStore;
        this.tokenDemoService = tokenDemoService;
    }

    @PostMapping("/chat")
    public AgentResponse chat(@RequestBody AgentRequest request) {
        log.info("POST /api/agent/chat — prompt length: {}, sessionId: {}",
                request.prompt() != null ? request.prompt().length() : 0,
                request.sessionId());
        return chatAgent.run(request);
    }

    @PostMapping("/reset")
    public void reset(@RequestBody AgentResetRequest request) {
        log.info("POST /api/agent/reset — sessionId: {}", request.sessionId());
        chatAgent.resetSession(request.sessionId());
    }

    @GetMapping("/token-scenario")
    public TokenScenarioResult tokenScenario(@RequestParam String scenario) {
        log.info("GET /api/agent/token-scenario — сценарий: {}", scenario);
        return tokenDemoService.runScenario(scenario);
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
        return new AgentHistoryResponse(sessionId, conversationStore.getHistory(sessionId));
    }
}

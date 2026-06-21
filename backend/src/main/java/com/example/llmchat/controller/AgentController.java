package com.example.llmchat.controller;

import com.example.llmchat.agent.ChatAgent;
import com.example.llmchat.agent.ContextStrategy;
import com.example.llmchat.agent.ConversationStore;
import com.example.llmchat.auth.AuthContext;
import com.example.llmchat.auth.AuthenticatedUser;
import com.example.llmchat.dto.AgentHistoryResponse;
import com.example.llmchat.dto.AgentRequest;
import com.example.llmchat.dto.AgentResetRequest;
import com.example.llmchat.dto.AgentResponse;
import com.example.llmchat.dto.MemorySnapshotResponse;
import com.example.llmchat.memory.MemoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final ChatAgent chatAgent;
    private final ConversationStore conversationStore;
    private final MemoryManager memoryManager;

    public AgentController(ChatAgent chatAgent, ConversationStore conversationStore, MemoryManager memoryManager) {
        this.chatAgent = chatAgent;
        this.conversationStore = conversationStore;
        this.memoryManager = memoryManager;
    }

    @PostMapping("/chat")
    public AgentResponse chat(@RequestBody AgentRequest request) {
        AuthenticatedUser user = AuthContext.requireUser();
        log.info("POST /api/agent/chat — user: {}, prompt length: {}, sessionId: {}",
                user.username(),
                request.prompt() != null ? request.prompt().length() : 0,
                request.sessionId());
        return chatAgent.run(request, user.userId());
    }

    @PostMapping("/reset")
    public void reset(@RequestBody AgentResetRequest request) {
        AuthenticatedUser user = AuthContext.requireUser();
        log.info("POST /api/agent/reset — user: {}, sessionId: {}", user.username(), request.sessionId());
        chatAgent.resetSession(request.sessionId(), user.userId());
    }

    @GetMapping("/history")
    public AgentHistoryResponse history(@RequestParam String sessionId) {
        AuthenticatedUser user = AuthContext.requireUser();
        log.info("GET /api/agent/history — user: {}, sessionId: {}", user.username(), sessionId);
        if (sessionId == null || sessionId.isBlank() || !conversationStore.hasSession(sessionId)) {
            throw new IllegalArgumentException("Сессия не найдена.");
        }
        if (!conversationStore.belongsToUser(sessionId, user.userId())) {
            throw new IllegalArgumentException("Сессия не принадлежит пользователю.");
        }

        return new AgentHistoryResponse(
                sessionId,
                conversationStore.getFullHistoryForDisplay(sessionId),
                conversationStore.getSummary(sessionId),
                ContextStrategy.SLIDING_WINDOW.name(),
                conversationStore.getFacts(sessionId),
                java.util.List.of(),
                null,
                -1);
    }

    @GetMapping("/memory")
    public MemorySnapshotResponse memory(@RequestParam String sessionId) {
        AuthenticatedUser user = AuthContext.requireUser();
        log.info("GET /api/agent/memory — user: {}, sessionId: {}", user.username(), sessionId);
        if (sessionId == null || sessionId.isBlank() || !conversationStore.hasSession(sessionId)) {
            throw new IllegalArgumentException("Сессия не найдена.");
        }
        if (!conversationStore.belongsToUser(sessionId, user.userId())) {
            throw new IllegalArgumentException("Сессия не принадлежит пользователю.");
        }
        return memoryManager.getMemorySnapshot(user.userId(), sessionId);
    }
}

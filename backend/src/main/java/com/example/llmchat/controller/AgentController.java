package com.example.llmchat.controller;

import com.example.llmchat.agent.ChatAgent;
import com.example.llmchat.agent.ConversationStore;
import com.example.llmchat.dto.AgentHistoryResponse;
import com.example.llmchat.dto.AgentRequest;
import com.example.llmchat.dto.AgentResetRequest;
import com.example.llmchat.dto.AgentResponse;
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

    public AgentController(ChatAgent chatAgent, ConversationStore conversationStore) {
        this.chatAgent = chatAgent;
        this.conversationStore = conversationStore;
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

    @GetMapping("/history")
    public AgentHistoryResponse history(@RequestParam String sessionId) {
        log.info("GET /api/agent/history — sessionId: {}", sessionId);
        if (sessionId == null || sessionId.isBlank() || !conversationStore.hasSession(sessionId)) {
            throw new IllegalArgumentException("Сессия не найдена.");
        }
        return new AgentHistoryResponse(sessionId, conversationStore.getHistory(sessionId));
    }
}

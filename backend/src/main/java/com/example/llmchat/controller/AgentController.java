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
import com.example.llmchat.dto.TaskSessionRequest;
import com.example.llmchat.dto.TaskStateResponse;
import com.example.llmchat.memory.MemoryManager;
import com.example.llmchat.task.TaskState;
import com.example.llmchat.task.TaskStateService;
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
    private final TaskStateService taskStateService;

    public AgentController(
            ChatAgent chatAgent,
            ConversationStore conversationStore,
            MemoryManager memoryManager,
            TaskStateService taskStateService) {
        this.chatAgent = chatAgent;
        this.conversationStore = conversationStore;
        this.memoryManager = memoryManager;
        this.taskStateService = taskStateService;
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

    @GetMapping("/task")
    public TaskStateResponse task(@RequestParam String sessionId) {
        AuthenticatedUser user = AuthContext.requireUser();
        ensureSessionAccess(sessionId, user.userId());
        return toTaskResponse(taskStateService.getState(sessionId).orElse(null));
    }

    @PostMapping("/task/pause")
    public TaskStateResponse pauseTask(@RequestBody TaskSessionRequest request) {
        AuthenticatedUser user = AuthContext.requireUser();
        ensureSessionAccess(request.sessionId(), user.userId());
        log.info("POST /api/agent/task/pause — user: {}, sessionId: {}", user.username(), request.sessionId());
        return toTaskResponse(taskStateService.pause(request.sessionId()));
    }

    @PostMapping("/task/resume")
    public TaskStateResponse resumeTask(@RequestBody TaskSessionRequest request) {
        AuthenticatedUser user = AuthContext.requireUser();
        ensureSessionAccess(request.sessionId(), user.userId());
        log.info("POST /api/agent/task/resume — user: {}, sessionId: {}", user.username(), request.sessionId());
        return toTaskResponse(taskStateService.resume(request.sessionId()));
    }

    private void ensureSessionAccess(String sessionId, String userId) {
        if (sessionId == null || sessionId.isBlank() || !conversationStore.hasSession(sessionId)) {
            throw new IllegalArgumentException("Сессия не найдена.");
        }
        if (!conversationStore.belongsToUser(sessionId, userId)) {
            throw new IllegalArgumentException("Сессия не принадлежит пользователю.");
        }
    }

    private TaskStateResponse toTaskResponse(TaskState state) {
        if (state == null) {
            return new TaskStateResponse(null, null, null, null, null, false, false);
        }
        return new TaskStateResponse(
                state.phase().id(),
                state.phase().displayLabel(),
                state.currentStep(),
                state.expectedAction(),
                state.taskTitle(),
                state.paused(),
                true);
    }
}

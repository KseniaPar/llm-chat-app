package com.example.llmchat.rag;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RagChatSessionStore {

    private final ConcurrentHashMap<String, RagChatSession> sessions = new ConcurrentHashMap<>();

    public String createSession() {
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, new RagChatSession(sessionId));
        return sessionId;
    }

    public RagChatSession getOrCreate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            String newId = createSession();
            return sessions.get(newId);
        }
        return sessions.computeIfAbsent(sessionId, RagChatSession::new);
    }

    public Optional<RagChatSession> find(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public void reset(String sessionId) {
        find(sessionId).ifPresent(RagChatSession::reset);
    }

    public void remove(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }
}

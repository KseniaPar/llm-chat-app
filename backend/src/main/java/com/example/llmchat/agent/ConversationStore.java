package com.example.llmchat.agent;

import com.example.llmchat.dto.AgentChatMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ConversationStore {

    private final ConcurrentHashMap<String, List<AgentChatMessage>> sessions = new ConcurrentHashMap<>();

    public String createSession() {
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, new ArrayList<>());
        return sessionId;
    }

    public boolean hasSession(String sessionId) {
        return sessionId != null && sessions.containsKey(sessionId);
    }

    public List<AgentChatMessage> getHistory(String sessionId) {
        List<AgentChatMessage> history = sessions.get(sessionId);
        if (history == null) {
            return List.of();
        }
        return List.copyOf(history);
    }

    public void append(String sessionId, String role, String content) {
        sessions.computeIfAbsent(sessionId, ignored -> new ArrayList<>())
                .add(new AgentChatMessage(role, content));
    }

    public void clear(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }
}

package com.example.llmchat.agent;

import com.example.llmchat.dto.AgentChatMessage;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationStore.class);

    private final ConversationPersistence persistence;
    private final ConcurrentHashMap<String, List<AgentChatMessage>> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SessionTokenTotals> tokenTotals = new ConcurrentHashMap<>();

    public ConversationStore(ConversationPersistence persistence) {
        this.persistence = persistence;
    }

    @PostConstruct
    void loadFromDisk() {
        Map<String, List<AgentChatMessage>> loaded = persistence.load();
        sessions.clear();
        tokenTotals.clear();
        for (Map.Entry<String, List<AgentChatMessage>> entry : loaded.entrySet()) {
            sessions.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        log.info("Загружено сессий диалога: {}", sessions.size());
    }

    public String createSession() {
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, new ArrayList<>());
        tokenTotals.put(sessionId, new SessionTokenTotals());
        persist();
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
        tokenTotals.computeIfAbsent(sessionId, ignored -> new SessionTokenTotals());
        persist();
    }

    public void seedHistory(String sessionId, List<AgentChatMessage> messages) {
        sessions.put(sessionId, new ArrayList<>(messages));
        tokenTotals.putIfAbsent(sessionId, new SessionTokenTotals());
        persist();
    }

    public SessionTokenTotals getTokenTotals(String sessionId) {
        return tokenTotals.computeIfAbsent(sessionId, ignored -> new SessionTokenTotals());
    }

    public void addTokenUsage(String sessionId, int promptTokens, int completionTokens) {
        tokenTotals.computeIfAbsent(sessionId, ignored -> new SessionTokenTotals())
                .add(promptTokens, completionTokens);
    }

    public void clear(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
            tokenTotals.remove(sessionId);
            persist();
        }
    }

    private void persist() {
        persistence.save(sessions);
    }

    public static final class SessionTokenTotals {
        private int promptTokens;
        private int completionTokens;

        public void add(int prompt, int completion) {
            promptTokens += prompt;
            completionTokens += completion;
        }

        public int promptTokens() {
            return promptTokens;
        }

        public int completionTokens() {
            return completionTokens;
        }

        public int totalTokens() {
            return promptTokens + completionTokens;
        }
    }
}

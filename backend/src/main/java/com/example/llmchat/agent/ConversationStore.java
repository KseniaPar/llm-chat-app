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
    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SessionTokenTotals> tokenTotals = new ConcurrentHashMap<>();

    public ConversationStore(ConversationPersistence persistence) {
        this.persistence = persistence;
    }

    @PostConstruct
    void loadFromDisk() {
        Map<String, SessionState> loaded = persistence.load();
        sessions.clear();
        tokenTotals.clear();
        sessions.putAll(loaded);
        log.info("Загружено сессий диалога: {}", sessions.size());
    }

    public String createSession() {
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, new SessionState());
        tokenTotals.put(sessionId, new SessionTokenTotals());
        persist();
        return sessionId;
    }

    public boolean hasSession(String sessionId) {
        return sessionId != null && sessions.containsKey(sessionId);
    }

    public List<AgentChatMessage> getHistory(String sessionId) {
        return getEffectiveHistory(sessionId);
    }

    public List<AgentChatMessage> getEffectiveHistory(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null || state.getMessages() == null) {
            return List.of();
        }
        return List.copyOf(state.getMessages());
    }

    public List<AgentChatMessage> getMessages(String sessionId) {
        return getEffectiveHistory(sessionId);
    }

    public String getSummary(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return null;
        }
        return state.getSummary();
    }

    public int getTotalMessageCount(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return 0;
        }
        return state.getTotalMessageCount();
    }

    public List<AgentChatMessage> getFullHistoryForDisplay(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return List.of();
        }

        List<AgentChatMessage> display = new ArrayList<>();
        if (state.getSummary() != null && !state.getSummary().isBlank()) {
            display.add(new AgentChatMessage("summary", state.getSummary()));
        }
        if (state.getMessages() != null) {
            display.addAll(state.getMessages());
        }
        return List.copyOf(display);
    }

    public void append(String sessionId, String role, String content) {
        SessionState state = sessions.computeIfAbsent(sessionId, ignored -> new SessionState());
        if (state.getMessages() == null) {
            state.setMessages(new ArrayList<>());
        }
        state.getMessages().add(new AgentChatMessage(role, content));
        state.setTotalMessageCount(state.getTotalMessageCount() + 1);
        tokenTotals.computeIfAbsent(sessionId, ignored -> new SessionTokenTotals());
        persist();
    }

    public void applyCompression(String sessionId, String summary, List<AgentChatMessage> keptMessages) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return;
        }
        state.setSummary(summary);
        state.setMessages(new ArrayList<>(keptMessages));
        persist();
    }

    public void seedHistory(String sessionId, List<AgentChatMessage> messages) {
        SessionState state = new SessionState();
        state.setMessages(new ArrayList<>(messages));
        state.setTotalMessageCount(messages.size());
        sessions.put(sessionId, state);
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

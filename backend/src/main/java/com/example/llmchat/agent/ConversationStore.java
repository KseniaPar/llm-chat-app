package com.example.llmchat.agent;

import com.example.llmchat.dto.AgentChatMessage;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        return createSession(null);
    }

    public String createSession(ContextStrategy strategy) {
        String sessionId = UUID.randomUUID().toString();
        SessionState state = new SessionState();
        state.setContextStrategy(strategy);
        sessions.put(sessionId, state);
        tokenTotals.put(sessionId, new SessionTokenTotals());
        persist();
        return sessionId;
    }

    public boolean hasSession(String sessionId) {
        return sessionId != null && sessions.containsKey(sessionId);
    }

    public SessionState getState(String sessionId) {
        return sessions.get(sessionId);
    }

    public ContextStrategy getContextStrategy(String sessionId) {
        SessionState state = sessions.get(sessionId);
        return state != null ? state.getContextStrategy() : null;
    }

    public void setContextStrategy(String sessionId, ContextStrategy strategy) {
        SessionState state = sessions.computeIfAbsent(sessionId, ignored -> new SessionState());
        state.setContextStrategy(strategy);
        persist();
    }

    public List<AgentChatMessage> getHistory(String sessionId) {
        return getEffectiveHistory(sessionId);
    }

    public List<AgentChatMessage> getEffectiveHistory(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return List.of();
        }

        if (state.getContextStrategy() == ContextStrategy.BRANCHING) {
            if (state.getForkMessageIndex() >= 0) {
                List<AgentChatMessage> combined = new ArrayList<>(state.getSharedPrefix());
                combined.addAll(state.getBranchMessages());
                return List.copyOf(combined);
            }
        }

        if (state.getMessages() == null) {
            return List.of();
        }
        return List.copyOf(state.getMessages());
    }

    public List<AgentChatMessage> getMessages(String sessionId) {
        return getEffectiveHistory(sessionId);
    }

    public List<AgentChatMessage> getStoredMessages(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return List.of();
        }
        if (state.getContextStrategy() == ContextStrategy.BRANCHING && state.getForkMessageIndex() >= 0) {
            List<AgentChatMessage> combined = new ArrayList<>(state.getSharedPrefix());
            combined.addAll(state.getBranchMessages());
            return List.copyOf(combined);
        }
        if (state.getMessages() == null) {
            return List.of();
        }
        return List.copyOf(state.getMessages());
    }

    public int getStoredMessageCount(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return 0;
        }
        if (state.getContextStrategy() == ContextStrategy.BRANCHING && state.getForkMessageIndex() >= 0) {
            return state.getSharedPrefix().size() + state.getBranchMessages().size();
        }
        return state.getMessages() != null ? state.getMessages().size() : 0;
    }

    public String getSummary(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return null;
        }
        return state.getSummary();
    }

    public Map<String, String> getFacts(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null || state.getFacts() == null || state.getFacts().isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(sanitizeFacts(state.getFacts()));
    }

    public void setFacts(String sessionId, Map<String, String> facts) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return;
        }
        state.setFacts(sanitizeFacts(facts));
        persist();
    }

    private static Map<String, String> sanitizeFacts(Map<String, String> facts) {
        if (facts == null || facts.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, String> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : facts.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                sanitized.put(entry.getKey(), entry.getValue());
            }
        }
        return sanitized;
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

        if (state.getContextStrategy() != ContextStrategy.BRANCHING
                && state.getSummary() != null
                && !state.getSummary().isBlank()) {
            display.add(new AgentChatMessage("summary", state.getSummary()));
        }

        if (state.getContextStrategy() == ContextStrategy.STICKY_FACTS
                && state.getFacts() != null
                && !state.getFacts().isEmpty()) {
            StringBuilder factsText = new StringBuilder();
            for (Map.Entry<String, String> entry : state.getFacts().entrySet()) {
                factsText.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
            display.add(new AgentChatMessage("facts", factsText.toString().trim()));
        }

        if (state.getContextStrategy() == ContextStrategy.BRANCHING && state.getForkMessageIndex() >= 0) {
            display.addAll(state.getSharedPrefix());
            display.addAll(state.getBranchMessages());
        } else if (state.getMessages() != null) {
            display.addAll(state.getMessages());
        }

        return List.copyOf(display);
    }

    public void append(String sessionId, String role, String content) {
        SessionState state = sessions.computeIfAbsent(sessionId, ignored -> new SessionState());
        if (state.getMessages() == null) {
            state.setMessages(new ArrayList<>());
        }

        AgentChatMessage message = new AgentChatMessage(role, content);

        if (state.getContextStrategy() == ContextStrategy.BRANCHING && state.getForkMessageIndex() >= 0) {
            state.getBranchMessages().add(message);
        } else {
            state.getMessages().add(message);
        }

        state.setTotalMessageCount(state.getTotalMessageCount() + 1);
        tokenTotals.computeIfAbsent(sessionId, ignored -> new SessionTokenTotals());
        persist();
    }

    public void trimToWindow(String sessionId, int windowSize) {
        SessionState state = sessions.get(sessionId);
        if (state == null || state.getMessages() == null) {
            return;
        }
        List<AgentChatMessage> messages = state.getMessages();
        if (messages.size() <= windowSize) {
            return;
        }
        state.setMessages(new ArrayList<>(messages.subList(messages.size() - windowSize, messages.size())));
        persist();
    }

    public List<AgentChatMessage> getWindowForContext(String sessionId, int windowSize) {
        List<AgentChatMessage> all = getStoredMessages(sessionId);
        if (all.size() <= windowSize) {
            return all;
        }
        return List.copyOf(all.subList(all.size() - windowSize, all.size()));
    }

    public List<AgentChatMessage> getBranchingContext(String sessionId, int windowSize) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return List.of();
        }

        if (state.getForkMessageIndex() < 0) {
            return getWindowForContext(sessionId, windowSize);
        }

        List<AgentChatMessage> prefix = state.getSharedPrefix();
        List<AgentChatMessage> branch = state.getBranchMessages();

        if (branch.size() <= windowSize) {
            List<AgentChatMessage> combined = new ArrayList<>(prefix);
            combined.addAll(branch);
            return List.copyOf(combined);
        }

        List<AgentChatMessage> combined = new ArrayList<>(prefix);
        combined.addAll(branch.subList(branch.size() - windowSize, branch.size()));
        return List.copyOf(combined);
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

    public int createCheckpoint(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            throw new IllegalArgumentException("Сессия не найдена.");
        }
        if (state.getContextStrategy() != ContextStrategy.BRANCHING) {
            state.setContextStrategy(ContextStrategy.BRANCHING);
        }

        List<AgentChatMessage> current = new ArrayList<>(getEffectiveHistory(sessionId));
        state.setSharedPrefix(current);
        state.setBranchMessages(new ArrayList<>());
        state.setForkMessageIndex(current.size());
        state.setBranchGroupId(sessionId);
        state.setActiveBranchId("main");
        state.setBranches(List.of(new BranchInfo("main", "Основная", sessionId)));
        persist();
        return state.getForkMessageIndex();
    }

    public List<BranchInfo> createBranches(String sessionId, List<String> branchNames) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            throw new IllegalArgumentException("Сессия не найдена.");
        }
        if (state.getForkMessageIndex() < 0) {
            throw new IllegalStateException("Сначала создайте checkpoint.");
        }

        List<AgentChatMessage> prefix = List.copyOf(state.getSharedPrefix());
        List<BranchInfo> branches = new ArrayList<>();

        for (int i = 0; i < branchNames.size(); i++) {
            String branchId = "branch-" + (char) ('a' + i);
            String label = branchNames.get(i);
            String branchSessionId = UUID.randomUUID().toString();

            SessionState branchState = new SessionState();
            branchState.setContextStrategy(ContextStrategy.BRANCHING);
            branchState.setSharedPrefix(new ArrayList<>(prefix));
            branchState.setBranchMessages(new ArrayList<>());
            branchState.setForkMessageIndex(prefix.size());
            branchState.setBranchGroupId(state.getBranchGroupId() != null ? state.getBranchGroupId() : sessionId);
            branchState.setActiveBranchId(branchId);
            branchState.setBranches(new ArrayList<>());

            sessions.put(branchSessionId, branchState);
            tokenTotals.putIfAbsent(branchSessionId, new SessionTokenTotals());
            branches.add(new BranchInfo(branchId, label, branchSessionId));
        }

        state.setBranches(branches);
        if (!branches.isEmpty()) {
            state.setActiveBranchId(branches.get(0).branchId());
        }
        persist();
        return branches;
    }

    public String switchBranch(String sessionId, String branchId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            throw new IllegalArgumentException("Сессия не найдена.");
        }

        BranchInfo target = findBranch(state, branchId);
        if (target == null && state.getBranches() != null) {
            for (BranchInfo branch : state.getBranches()) {
                if (branch.branchId().equals(branchId)) {
                    target = branch;
                    break;
                }
            }
        }

        if (target == null) {
            throw new IllegalArgumentException("Ветка не найдена: " + branchId);
        }

        state.setActiveBranchId(branchId);
        persist();
        return target.sessionId();
    }

    public List<BranchInfo> getBranches(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null || state.getBranches() == null) {
            return List.of();
        }
        return List.copyOf(state.getBranches());
    }

    public String getActiveBranchSessionId(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return sessionId;
        }

        if (state.getBranches() == null || state.getBranches().isEmpty()) {
            return sessionId;
        }

        String activeBranchId = state.getActiveBranchId();
        for (BranchInfo branch : state.getBranches()) {
            if (branch.branchId().equals(activeBranchId)) {
                return branch.sessionId();
            }
        }
        return state.getBranches().get(0).sessionId();
    }

    public int getForkMessageIndex(String sessionId) {
        SessionState state = sessions.get(sessionId);
        return state != null ? state.getForkMessageIndex() : -1;
    }

    private BranchInfo findBranch(SessionState state, String branchId) {
        if (state.getBranches() == null) {
            return null;
        }
        for (BranchInfo branch : state.getBranches()) {
            if (branch.branchId().equals(branchId)) {
                return branch;
            }
        }
        return null;
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

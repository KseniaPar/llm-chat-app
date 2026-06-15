package com.example.llmchat.agent;

import com.example.llmchat.dto.AgentChatMessage;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BranchingService {

    private static final List<String> DEFAULT_BRANCH_NAMES = List.of("Ветка A", "Ветка B");

    private final ConversationStore conversationStore;
    private final List<String> defaultBranchNames;

    public BranchingService(ConversationStore conversationStore) {
        this.conversationStore = conversationStore;
        this.defaultBranchNames = DEFAULT_BRANCH_NAMES;
    }

    public int createCheckpoint(String sessionId) {
        return conversationStore.createCheckpoint(sessionId);
    }

    public List<BranchInfo> createBranches(String sessionId) {
        return conversationStore.createBranches(sessionId, defaultBranchNames);
    }

    public String switchBranch(String sessionId, String branchId) {
        return conversationStore.switchBranch(sessionId, branchId);
    }

    public List<BranchInfo> getBranches(String sessionId) {
        return conversationStore.getBranches(sessionId);
    }

    public String resolveActiveSessionId(String sessionId) {
        return conversationStore.getActiveBranchSessionId(sessionId);
    }

    public List<AgentChatMessage> getBranchingContext(String sessionId, int windowSize) {
        String activeSessionId = resolveActiveSessionId(sessionId);
        return conversationStore.getBranchingContext(activeSessionId, windowSize);
    }
}

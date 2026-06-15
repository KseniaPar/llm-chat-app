package com.example.llmchat.agent;

import com.example.llmchat.dto.AgentChatMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SessionState {

    private String summary;
    private List<AgentChatMessage> messages = new ArrayList<>();
    private int totalMessageCount;
    private ContextStrategy contextStrategy;
    private Map<String, String> facts = new LinkedHashMap<>();
    private String branchGroupId;
    private String activeBranchId;
    private List<BranchInfo> branches = new ArrayList<>();
    private int forkMessageIndex = -1;
    private List<AgentChatMessage> sharedPrefix = new ArrayList<>();
    private List<AgentChatMessage> branchMessages = new ArrayList<>();

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<AgentChatMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<AgentChatMessage> messages) {
        this.messages = messages != null ? new ArrayList<>(messages) : new ArrayList<>();
    }

    public int getTotalMessageCount() {
        return totalMessageCount;
    }

    public void setTotalMessageCount(int totalMessageCount) {
        this.totalMessageCount = totalMessageCount;
    }

    public ContextStrategy getContextStrategy() {
        return contextStrategy;
    }

    public void setContextStrategy(ContextStrategy contextStrategy) {
        this.contextStrategy = contextStrategy;
    }

    public Map<String, String> getFacts() {
        return facts;
    }

    public void setFacts(Map<String, String> facts) {
        this.facts = facts != null ? new LinkedHashMap<>(facts) : new LinkedHashMap<>();
    }

    public String getBranchGroupId() {
        return branchGroupId;
    }

    public void setBranchGroupId(String branchGroupId) {
        this.branchGroupId = branchGroupId;
    }

    public String getActiveBranchId() {
        return activeBranchId;
    }

    public void setActiveBranchId(String activeBranchId) {
        this.activeBranchId = activeBranchId;
    }

    public List<BranchInfo> getBranches() {
        return branches;
    }

    public void setBranches(List<BranchInfo> branches) {
        this.branches = branches != null ? new ArrayList<>(branches) : new ArrayList<>();
    }

    public int getForkMessageIndex() {
        return forkMessageIndex;
    }

    public void setForkMessageIndex(int forkMessageIndex) {
        this.forkMessageIndex = forkMessageIndex;
    }

    public List<AgentChatMessage> getSharedPrefix() {
        return sharedPrefix;
    }

    public void setSharedPrefix(List<AgentChatMessage> sharedPrefix) {
        this.sharedPrefix = sharedPrefix != null ? new ArrayList<>(sharedPrefix) : new ArrayList<>();
    }

    public List<AgentChatMessage> getBranchMessages() {
        return branchMessages;
    }

    public void setBranchMessages(List<AgentChatMessage> branchMessages) {
        this.branchMessages = branchMessages != null ? new ArrayList<>(branchMessages) : new ArrayList<>();
    }
}

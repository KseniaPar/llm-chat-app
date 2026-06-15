package com.example.llmchat.agent;

import com.example.llmchat.dto.AgentChatMessage;

import java.util.ArrayList;
import java.util.List;

public class SessionState {

    private String summary;
    private List<AgentChatMessage> messages = new ArrayList<>();
    private int totalMessageCount;

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
}

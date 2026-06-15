package com.example.llmchat.dto;

public record AgentRequest(String prompt, String sessionId, Boolean compressionEnabled) {

    public AgentRequest(String prompt, String sessionId) {
        this(prompt, sessionId, null);
    }
}

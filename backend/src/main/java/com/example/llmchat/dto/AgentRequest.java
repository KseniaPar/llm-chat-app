package com.example.llmchat.dto;

public record AgentRequest(
        String prompt,
        String sessionId,
        Boolean compressionEnabled,
        String contextStrategy,
        String branchId) {

    public AgentRequest(String prompt, String sessionId) {
        this(prompt, sessionId, null, null, null);
    }

    public AgentRequest(String prompt, String sessionId, Boolean compressionEnabled) {
        this(prompt, sessionId, compressionEnabled, null, null);
    }
}

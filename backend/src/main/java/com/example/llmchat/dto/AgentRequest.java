package com.example.llmchat.dto;

public record AgentRequest(
        String prompt,
        String sessionId,
        Boolean compressionEnabled,
        String contextStrategy,
        String branchId,
        Boolean agentDrivenMcp) {

    public AgentRequest(String prompt, String sessionId) {
        this(prompt, sessionId, null, null, null, null);
    }

    public AgentRequest(String prompt, String sessionId, Boolean compressionEnabled) {
        this(prompt, sessionId, compressionEnabled, null, null, null);
    }
}

package com.example.llmchat.dto;

public record SimulatorTurnResponse(
        String userMessage,
        String agentResponse,
        String sessionId,
        boolean finished
) {
}

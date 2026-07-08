package com.example.llmchat.dto;

import java.util.List;

public record LocalLlmAgentHistoryResponse(String sessionId, List<AgentChatMessage> messages) {
}

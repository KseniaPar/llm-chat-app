package com.example.llmchat.dto;

import java.util.List;

public record AgentHistoryResponse(String sessionId, List<AgentChatMessage> messages) {
}

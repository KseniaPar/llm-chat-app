package com.example.llmchat.dto;

import java.util.List;

public record AgentResponse(String response, String sessionId, List<String> logs) {
}

package com.example.llmchat.dto;

import java.util.List;
import java.util.Map;

public record AgentHistoryResponse(
        String sessionId,
        List<AgentChatMessage> messages,
        String summary,
        String contextStrategy,
        Map<String, String> facts,
        List<BranchInfoDto> branches,
        String activeBranchId,
        int forkMessageIndex) {

    public AgentHistoryResponse(String sessionId, List<AgentChatMessage> messages, String summary) {
        this(sessionId, messages, summary, null, Map.of(), List.of(), null, -1);
    }
}

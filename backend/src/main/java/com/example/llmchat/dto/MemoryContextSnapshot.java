package com.example.llmchat.dto;

import java.util.List;
import java.util.Map;

public record MemoryContextSnapshot(
        List<AgentChatMessage> shortTermInContext,
        Map<String, String> workingFactsInContext,
        String workingSummaryInContext,
        Map<String, Map<String, String>> longTermInContext,
        List<String> memoryLogs) {
}

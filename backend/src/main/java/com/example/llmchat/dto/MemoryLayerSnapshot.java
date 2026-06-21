package com.example.llmchat.dto;

import java.util.List;
import java.util.Map;

public record MemoryLayerSnapshot(
        String layer,
        List<AgentChatMessage> messages,
        Map<String, String> facts,
        String summary,
        Map<String, Map<String, String>> longTerm) {
}

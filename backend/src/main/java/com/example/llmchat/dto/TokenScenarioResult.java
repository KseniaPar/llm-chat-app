package com.example.llmchat.dto;

import java.util.List;

public record TokenScenarioResult(
        String id,
        String title,
        String description,
        List<TokenDemoStep> steps,
        List<AgentChatMessage> dialogHistory,
        String outcome,
        boolean failed,
        String liveApiResponse,
        String liveApiError,
        Integer liveApiStatusCode,
        int modelContextLimit) {

    public TokenScenarioResult(
            String id,
            String title,
            String description,
            List<TokenDemoStep> steps,
            List<AgentChatMessage> dialogHistory,
            String outcome,
            boolean failed,
            int modelContextLimit) {
        this(id, title, description, steps, dialogHistory, outcome, failed, null, null, null, modelContextLimit);
    }
}

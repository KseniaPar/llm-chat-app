package com.example.llmchat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TokenScenarioStreamEvent(
        String event,
        String id,
        String title,
        String description,
        Integer modelContextLimit,
        Integer turn,
        String content,
        TokenDemoStep step,
        String outcome,
        Boolean failed,
        String liveApiError,
        Integer liveApiStatusCode) {

    public static TokenScenarioStreamEvent start(
            String id, String title, String description, int modelContextLimit) {
        return new TokenScenarioStreamEvent(
                "start", id, title, description, modelContextLimit,
                null, null, null, null, null, null, null);
    }

    public static TokenScenarioStreamEvent user(int turn, String content) {
        return new TokenScenarioStreamEvent(
                "user", null, null, null, null,
                turn, content, null, null, null, null, null);
    }

    public static TokenScenarioStreamEvent turn(int turn, String content, TokenDemoStep step) {
        return new TokenScenarioStreamEvent(
                "turn", null, null, null, null,
                turn, content, step, null, null, null, null);
    }

    public static TokenScenarioStreamEvent done(
            String outcome, boolean failed, String liveApiError, Integer liveApiStatusCode) {
        return new TokenScenarioStreamEvent(
                "done", null, null, null, null,
                null, null, null, outcome, failed, liveApiError, liveApiStatusCode);
    }
}

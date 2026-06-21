package com.example.llmchat.dto;

public record TokenCompareResponse(
        TokenScenarioResult shortDialog,
        TokenScenarioResult longDialog,
        TokenScenarioResult overflowDialog,
        int modelContextLimit) {
}

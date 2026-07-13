package com.example.llmchat.dto;

import java.util.List;

public record LocalLlmServiceVerifyResponse(
        boolean allPassed,
        List<LocalLlmServiceVerifyCheckDto> checks,
        String summary) {
}

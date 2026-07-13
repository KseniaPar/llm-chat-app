package com.example.llmchat.dto;

import java.util.List;

public record PlatformVerifyResponse(
        boolean allPassed,
        List<LocalLlmServiceVerifyCheckDto> checks,
        String summary) {
}

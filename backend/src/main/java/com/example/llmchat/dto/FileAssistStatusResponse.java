package com.example.llmchat.dto;

import java.util.List;

public record FileAssistStatusResponse(
        String platformVersion,
        boolean llmReady,
        String model,
        boolean mcpConnected,
        boolean filesToolAvailable,
        String repoRoot,
        List<String> writeAllowlist) {
}

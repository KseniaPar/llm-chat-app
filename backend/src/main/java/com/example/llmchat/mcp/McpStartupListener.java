package com.example.llmchat.mcp;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class McpStartupListener {

    private final McpConnectionService mcpConnectionService;

    public McpStartupListener(McpConnectionService mcpConnectionService) {
        this.mcpConnectionService = mcpConnectionService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        mcpConnectionService.logStartupSummary();
    }
}

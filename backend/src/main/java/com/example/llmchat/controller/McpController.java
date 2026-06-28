package com.example.llmchat.controller;

import com.example.llmchat.dto.McpStatusResponse;
import com.example.llmchat.dto.McpToolsResponse;
import com.example.llmchat.mcp.McpConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mcp")
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private final McpConnectionService mcpConnectionService;

    public McpController(McpConnectionService mcpConnectionService) {
        this.mcpConnectionService = mcpConnectionService;
    }

    @GetMapping("/status")
    public McpStatusResponse status() {
        log.debug("GET /api/mcp/status");
        return mcpConnectionService.getStatus();
    }

    @GetMapping("/tools")
    public McpToolsResponse tools() {
        log.info("GET /api/mcp/tools");
        return mcpConnectionService.getToolsSnapshot();
    }

    @PostMapping("/reconnect")
    public McpToolsResponse reconnect() {
        log.info("POST /api/mcp/reconnect");
        return mcpConnectionService.refreshToolsSnapshot();
    }
}

package com.example.llmchat.config;

import com.example.llmchat.fileassist.FileWriteCollector;
import com.example.llmchat.mcp.McpTextEncoding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

/**
 * Records tool calls and captures full writeFile JSON for Day 34 diff extraction.
 */
public final class FileAssistRecordingToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(FileAssistRecordingToolCallback.class);

    private final ToolCallback delegate;
    private final String serverName;

    public FileAssistRecordingToolCallback(ToolCallback delegate, String serverName) {
        this.delegate = delegate;
        this.serverName = serverName;
    }

    @Override
    public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        long started = System.currentTimeMillis();
        String toolName = getToolDefinition().name();
        log.info("MCP tool call -> {}.{} args={}", serverName, toolName, toolInput);
        String result = delegate.call(toolInput);
        result = looksLikeJson(result) ? McpTextEncoding.normalizeJson(result) : McpTextEncoding.normalize(result);
        long duration = System.currentTimeMillis() - started;
        log.info("MCP tool result <- {}.{} ({} ms)", serverName, toolName, duration);
        if ("writeFile".equals(toolName) && result != null) {
            FileWriteCollector.record(result);
        }
        String preview = result != null && result.length() > 500
                ? result.substring(0, 500) + "..."
                : result;
        AgentChatClientConfig.recordToolCall(
                serverName,
                toolName,
                McpTextEncoding.normalize(toolInput),
                preview,
                duration);
        return result;
    }

    private static boolean looksLikeJson(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }
}

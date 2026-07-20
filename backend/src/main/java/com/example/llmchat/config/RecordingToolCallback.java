package com.example.llmchat.config;

import com.example.llmchat.mcp.McpTextEncoding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

/**
 * Wraps a {@link ToolCallback} and appends each invocation to
 * {@link AgentChatClientConfig}'s thread-local recording.
 */
public final class RecordingToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(RecordingToolCallback.class);

    private final ToolCallback delegate;
    private final String serverName;

    public RecordingToolCallback(ToolCallback delegate, String serverName) {
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
        log.info("MCP tool call -> {}.{} args={}", serverName, getToolDefinition().name(), toolInput);
        String result = delegate.call(toolInput);
        result = looksLikeJson(result) ? McpTextEncoding.normalizeJson(result) : McpTextEncoding.normalize(result);
        long duration = System.currentTimeMillis() - started;
        log.info("MCP tool result <- {}.{} ({} ms)", serverName, getToolDefinition().name(), duration);
        String preview = result != null && result.length() > 500
                ? result.substring(0, 500) + "..."
                : result;
        AgentChatClientConfig.recordToolCall(
                serverName,
                getToolDefinition().name(),
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

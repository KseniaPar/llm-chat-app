package com.example.llmchat.config;

import com.example.llmchat.mcp.McpTextEncoding;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

@Configuration
public class AgentChatClientConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentChatClientConfig.class);

    public static final String STUDY_SERVER_NAME = "mcp-study";
    public static final String PIPELINE_SERVER_NAME = "mcp-pipeline";
    public static final String SCHEDULER_SERVER_NAME = "mcp-scheduler";
    private static final Set<String> AGENT_MCP_SERVER_NAMES = Set.of(
            STUDY_SERVER_NAME, PIPELINE_SERVER_NAME, SCHEDULER_SERVER_NAME);
    private static final Set<String> AGENT_EXCLUDED_TOOL_NAMES = Set.of(
            "schedulePeriodicSummary", "getSummary");

    private static final ThreadLocal<List<McpToolCallLogDtoHolder>> TOOL_CALLS =
            ThreadLocal.withInitial(CopyOnWriteArrayList::new);

    @Bean
    @Lazy
    ToolCallback[] agentToolCallbacks(ObjectProvider<List<McpSyncClient>> mcpClientsProvider) {
        List<McpSyncClient> clients = mcpClientsProvider.getIfAvailable();
        if (clients == null || clients.isEmpty()) {
            return new ToolCallback[0];
        }

        for (McpSyncClient client : clients) {
            try {
                client.initialize();
            } catch (Exception exception) {
                // logged at startup by McpConnectionService
            }
        }

        List<McpSyncClient> agentClients = clients.stream()
                .filter(this::isAgentMcpServer)
                .toList();

        if (agentClients.isEmpty()) {
            log.warn("No MCP agent servers found — ChatClient will run without study/scheduler tools");
            return new ToolCallback[0];
        }

        log.info(
                "ChatClient wired to MCP agent servers: {}",
                agentClients.stream().map(this::resolveServerName).toList());

        List<ToolCallback> wrapped = new ArrayList<>();
        for (McpSyncClient client : agentClients) {
            String serverName = resolveServerName(client);
            ToolCallback[] raw = new SyncMcpToolCallbackProvider(List.of(client)).getToolCallbacks();
            for (ToolCallback callback : raw) {
                String toolName = callback.getToolDefinition().name();
                if (AGENT_EXCLUDED_TOOL_NAMES.contains(toolName)) {
                    continue;
                }
                wrapped.add(new RecordingToolCallback(callback, serverName));
            }
        }
        return wrapped.toArray(ToolCallback[]::new);
    }

    @Bean
    @Lazy
    ChatClient agentChatClient(ChatClient.Builder chatClientBuilder, ToolCallback[] agentToolCallbacks) {
        if (agentToolCallbacks.length == 0) {
            return chatClientBuilder.build();
        }
        return chatClientBuilder
                .defaultToolCallbacks(agentToolCallbacks)
                .build();
    }

    private boolean isAgentMcpServer(McpSyncClient client) {
        String name = resolveServerName(client);
        return name != null && AGENT_MCP_SERVER_NAMES.contains(name);
    }

    private String resolveServerName(McpSyncClient client) {
        try {
            McpSchema.Implementation info = client.getServerInfo();
            return info != null ? info.name() : null;
        } catch (Exception exception) {
            return null;
        }
    }

    public static void beginToolCallRecording() {
        TOOL_CALLS.get().clear();
    }

    public static List<com.example.llmchat.dto.McpToolCallLogDto> drainToolCallRecording() {
        List<com.example.llmchat.dto.McpToolCallLogDto> copy = TOOL_CALLS.get().stream()
                .map(McpToolCallLogDtoHolder::toDto)
                .toList();
        TOOL_CALLS.get().clear();
        return copy;
    }

    private record McpToolCallLogDtoHolder(
            String serverName, String toolName, String arguments, String resultPreview, long durationMs) {
        com.example.llmchat.dto.McpToolCallLogDto toDto() {
            return new com.example.llmchat.dto.McpToolCallLogDto(serverName, toolName, arguments, resultPreview, durationMs);
        }
    }

    private static final class RecordingToolCallback implements ToolCallback {

        private final ToolCallback delegate;
        private final String serverName;

        private RecordingToolCallback(ToolCallback delegate, String serverName) {
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
            TOOL_CALLS.get().add(new McpToolCallLogDtoHolder(
                    serverName,
                    getToolDefinition().name(),
                    McpTextEncoding.normalize(toolInput),
                    preview,
                    duration));
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
}

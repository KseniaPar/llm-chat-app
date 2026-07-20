package com.example.llmchat.config;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
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
    public static final String GIT_SERVER_NAME = "mcp-git";
    public static final String TICKETS_SERVER_NAME = "mcp-tickets";
    public static final String FILES_SERVER_NAME = "mcp-files";
    private static final Set<String> AGENT_MCP_SERVER_NAMES = Set.of(
            STUDY_SERVER_NAME, PIPELINE_SERVER_NAME, SCHEDULER_SERVER_NAME, GIT_SERVER_NAME);
    private static final Set<String> AGENT_EXCLUDED_TOOL_NAMES = Set.of(
            "schedulePeriodicSummary", "getSummary");

    private static final ThreadLocal<List<McpToolCallLogDtoHolder>> TOOL_CALLS =
            ThreadLocal.withInitial(CopyOnWriteArrayList::new);

    @Bean(name = "agentToolCallbacks")
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

    @Bean(name = "agentChatClient")
    @Lazy
    ChatClient agentChatClient(
            ChatClient.Builder chatClientBuilder,
            @Qualifier("agentToolCallbacks") ToolCallback[] agentToolCallbacks) {
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

    public static void recordToolCall(
            String serverName, String toolName, String arguments, String resultPreview, long durationMs) {
        TOOL_CALLS.get().add(new McpToolCallLogDtoHolder(
                serverName, toolName, arguments, resultPreview, durationMs));
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
}

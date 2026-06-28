package com.example.llmchat.config;

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
import java.util.concurrent.CopyOnWriteArrayList;

@Configuration
public class AgentChatClientConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentChatClientConfig.class);

    public static final String STUDY_SERVER_NAME = "mcp-study";
    private static final ThreadLocal<List<McpToolCallLogDtoHolder>> TOOL_CALLS =
            ThreadLocal.withInitial(CopyOnWriteArrayList::new);

    @Bean
    @Lazy
    ToolCallback[] studyToolCallbacks(ObjectProvider<List<McpSyncClient>> mcpClientsProvider) {
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

        List<McpSyncClient> studyClients = clients.stream()
                .filter(this::isStudyServer)
                .toList();

        if (studyClients.isEmpty()) {
            log.warn("MCP study server not found — ChatClient will run without study tools");
            return new ToolCallback[0];
        }

        log.info("ChatClient wired to MCP study server ({} client(s))", studyClients.size());

        ToolCallback[] raw = new SyncMcpToolCallbackProvider(studyClients).getToolCallbacks();
        List<ToolCallback> wrapped = new ArrayList<>();
        for (ToolCallback callback : raw) {
            wrapped.add(new RecordingToolCallback(callback, STUDY_SERVER_NAME));
        }
        return wrapped.toArray(ToolCallback[]::new);
    }

    @Bean
    @Lazy
    ChatClient agentChatClient(ChatClient.Builder chatClientBuilder, ToolCallback[] studyToolCallbacks) {
        if (studyToolCallbacks.length == 0) {
            return chatClientBuilder.build();
        }
        return chatClientBuilder
                .defaultToolCallbacks(studyToolCallbacks)
                .build();
    }

    private boolean isStudyServer(McpSyncClient client) {
        try {
            var info = client.getServerInfo();
            return info != null && STUDY_SERVER_NAME.equalsIgnoreCase(info.name());
        } catch (Exception exception) {
            return false;
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
            long duration = System.currentTimeMillis() - started;
            log.info("MCP tool result <- {}.{} ({} ms)", serverName, getToolDefinition().name(), duration);
            String preview = result != null && result.length() > 500
                    ? result.substring(0, 500) + "..."
                    : result;
            TOOL_CALLS.get().add(new McpToolCallLogDtoHolder(
                    serverName,
                    getToolDefinition().name(),
                    toolInput,
                    preview,
                    duration));
            return result;
        }
    }
}

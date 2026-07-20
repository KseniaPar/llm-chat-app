package com.example.llmchat.config;

import com.example.llmchat.devassist.DevAssistProjectTools;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.ArrayList;
import java.util.List;

/**
 * ChatClient for Day 31 developer assistant: mcp-git tools + project docs RAG tool.
 */
@Configuration
public class DevAssistChatClientConfig {

    private static final Logger log = LoggerFactory.getLogger(DevAssistChatClientConfig.class);

    @Bean(name = "devAssistToolCallbacks")
    @Lazy
    ToolCallback[] devAssistToolCallbacks(
            ObjectProvider<List<McpSyncClient>> mcpClientsProvider,
            DevAssistProjectTools projectTools) {
        List<ToolCallback> wrapped = new ArrayList<>();

        ToolCallback[] ragTools = MethodToolCallbackProvider.builder()
                .toolObjects(projectTools)
                .build()
                .getToolCallbacks();
        for (ToolCallback callback : ragTools) {
            wrapped.add(new RecordingToolCallback(callback, DevAssistProjectTools.TOOL_SERVER_NAME));
        }

        List<McpSyncClient> clients = mcpClientsProvider.getIfAvailable();
        if (clients != null) {
            for (McpSyncClient client : clients) {
                try {
                    client.initialize();
                } catch (Exception ignored) {
                    // already initialized
                }
                if (!isGitServer(client)) {
                    continue;
                }
                String serverName = resolveServerName(client);
                ToolCallback[] raw = new SyncMcpToolCallbackProvider(List.of(client)).getToolCallbacks();
                for (ToolCallback callback : raw) {
                    wrapped.add(new RecordingToolCallback(callback, serverName));
                }
                log.info("DevAssist ChatClient wired to MCP server '{}' ({} tool(s))",
                        serverName, raw.length);
            }
        }

        if (wrapped.isEmpty()) {
            log.warn("DevAssist has no tools — RAG bean missing and mcp-git not connected");
        } else {
            log.info("DevAssist tools: {}",
                    wrapped.stream().map(t -> t.getToolDefinition().name()).toList());
        }
        return wrapped.toArray(ToolCallback[]::new);
    }

    /**
     * Plain ChatClient; tools are attached per request so we never share defaults with the study agent.
     */
    @Bean(name = "devAssistChatClient")
    @Lazy
    ChatClient devAssistChatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder.build();
    }

    private boolean isGitServer(McpSyncClient client) {
        String name = resolveServerName(client);
        return AgentChatClientConfig.GIT_SERVER_NAME.equalsIgnoreCase(name)
                || "git".equalsIgnoreCase(name);
    }

    private String resolveServerName(McpSyncClient client) {
        try {
            McpSchema.Implementation info = client.getServerInfo();
            return info != null ? info.name() : null;
        } catch (Exception exception) {
            return null;
        }
    }
}

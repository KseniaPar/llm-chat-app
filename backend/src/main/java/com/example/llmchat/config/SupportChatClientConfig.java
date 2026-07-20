package com.example.llmchat.config;

import com.example.llmchat.support.SupportFaqTools;
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

@Configuration
public class SupportChatClientConfig {

    private static final Logger log = LoggerFactory.getLogger(SupportChatClientConfig.class);

    @Bean(name = "supportToolCallbacks")
    @Lazy
    ToolCallback[] supportToolCallbacks(
            ObjectProvider<List<McpSyncClient>> mcpClientsProvider,
            SupportFaqTools faqTools) {
        List<ToolCallback> wrapped = new ArrayList<>();

        ToolCallback[] ragTools = MethodToolCallbackProvider.builder()
                .toolObjects(faqTools)
                .build()
                .getToolCallbacks();
        for (ToolCallback callback : ragTools) {
            wrapped.add(new RecordingToolCallback(callback, SupportFaqTools.TOOL_SERVER_NAME));
        }

        List<McpSyncClient> clients = mcpClientsProvider.getIfAvailable();
        if (clients != null) {
            for (McpSyncClient client : clients) {
                try {
                    client.initialize();
                } catch (Exception ignored) {
                    // already initialized
                }
                if (!isTicketsServer(client)) {
                    continue;
                }
                String serverName = resolveServerName(client);
                ToolCallback[] raw = new SyncMcpToolCallbackProvider(List.of(client)).getToolCallbacks();
                for (ToolCallback callback : raw) {
                    wrapped.add(new RecordingToolCallback(callback, serverName));
                }
                log.info("Support ChatClient wired to MCP server '{}' ({} tool(s))",
                        serverName, raw.length);
            }
        }

        log.info("Support tools: {}",
                wrapped.stream().map(t -> t.getToolDefinition().name()).toList());
        return wrapped.toArray(ToolCallback[]::new);
    }

    @Bean(name = "supportChatClient")
    @Lazy
    ChatClient supportChatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder.build();
    }

    private boolean isTicketsServer(McpSyncClient client) {
        String name = resolveServerName(client);
        return AgentChatClientConfig.TICKETS_SERVER_NAME.equalsIgnoreCase(name)
                || "tickets".equalsIgnoreCase(name);
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

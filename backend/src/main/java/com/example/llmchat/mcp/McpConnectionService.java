package com.example.llmchat.mcp;

import com.example.llmchat.dto.McpStatusResponse;
import com.example.llmchat.dto.McpToolDto;
import com.example.llmchat.dto.McpToolsResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class McpConnectionService {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionService.class);

    private final ObjectProvider<List<McpSyncClient>> mcpClientsProvider;
    private final ObjectMapper objectMapper;
    private final String sandboxPath;
    private final boolean mcpEnabled;

    private volatile McpToolsResponse cachedSnapshot;
    private volatile String lastError;

    public McpConnectionService(
            ObjectProvider<List<McpSyncClient>> mcpClientsProvider,
            ObjectMapper objectMapper,
            @Value("${app.mcp.sandbox.absolute:}") String sandboxPath,
            @Value("${app.mcp.enabled:true}") boolean mcpEnabled) {
        this.mcpClientsProvider = mcpClientsProvider;
        this.objectMapper = objectMapper;
        this.sandboxPath = sandboxPath;
        this.mcpEnabled = mcpEnabled;
    }

    public McpStatusResponse getStatus() {
        McpToolsResponse snapshot = getToolsSnapshot(false);
        return new McpStatusResponse(
                snapshot.connected(),
                snapshot.toolCount(),
                snapshot.servers(),
                snapshot.sandboxPath(),
                snapshot.message(),
                snapshot.checkedAt());
    }

    public McpToolsResponse getToolsSnapshot() {
        return getToolsSnapshot(true);
    }

    public McpToolsResponse refreshToolsSnapshot() {
        cachedSnapshot = null;
        return getToolsSnapshot(true);
    }

    public void logStartupSummary() {
        McpToolsResponse snapshot = refreshToolsSnapshot();
        if (snapshot.connected()) {
            log.info(
                    "MCP connected: {} server(s), {} tool(s). Sandbox: {}",
                    snapshot.servers().size(),
                    snapshot.toolCount(),
                    snapshot.sandboxPath());
            snapshot.tools().stream()
                    .map(McpToolDto::name)
                    .limit(12)
                    .forEach(toolName -> log.info("  MCP tool: {}", toolName));
        } else {
            log.warn("MCP is not connected: {}", snapshot.message());
        }
    }

    private McpToolsResponse getToolsSnapshot(boolean useCache) {
        if (useCache && cachedSnapshot != null) {
            return cachedSnapshot;
        }

        Instant checkedAt = Instant.now();
        if (!mcpEnabled) {
            return cache(new McpToolsResponse(
                    false,
                    0,
                    List.of(),
                    List.of(),
                    sandboxPath,
                    "MCP отключён (app.mcp.enabled=false).",
                    checkedAt));
        }

        List<McpSyncClient> clients = mcpClientsProvider.getIfAvailable();
        if (clients == null || clients.isEmpty()) {
            String message = lastError != null
                    ? lastError
                    : "MCP-клиенты не инициализированы. Проверьте Node.js/npx и app.mcp.enabled.";
            return cache(new McpToolsResponse(false, 0, List.of(), List.of(), sandboxPath, message, checkedAt));
        }

        List<McpToolDto> tools = new ArrayList<>();
        Set<String> servers = new LinkedHashSet<>();
        try {
            for (int index = 0; index < clients.size(); index++) {
                McpSyncClient client = clients.get(index);
                String serverName = resolveServerName(client, index);
                servers.add(serverName);
                McpSchema.ListToolsResult toolsResult = client.listTools();
                if (toolsResult == null || toolsResult.tools() == null) {
                    continue;
                }
                for (McpSchema.Tool tool : toolsResult.tools()) {
                    tools.add(new McpToolDto(
                            serverName,
                            tool.name(),
                            Optional.ofNullable(tool.description()).orElse(""),
                            toJsonNode(tool.inputSchema())));
                }
            }
            lastError = null;
            return cache(new McpToolsResponse(
                    true,
                    tools.size(),
                    List.copyOf(servers),
                    List.copyOf(tools),
                    sandboxPath,
                    "Подключено к MCP. Инструменты доступны для просмотра (вызов — Day 17).",
                    checkedAt));
        } catch (Exception exception) {
            lastError = exception.getMessage();
            log.error("Failed to list MCP tools", exception);
            return cache(new McpToolsResponse(
                    false,
                    0,
                    List.copyOf(servers),
                    List.of(),
                    sandboxPath,
                    "Ошибка MCP: " + exception.getMessage(),
                    checkedAt));
        }
    }

    private McpToolsResponse cache(McpToolsResponse snapshot) {
        cachedSnapshot = snapshot;
        return snapshot;
    }

    private String resolveServerName(McpSyncClient client, int index) {
        try {
            McpSchema.Implementation serverInfo = client.getServerInfo();
            if (serverInfo != null && serverInfo.name() != null && !serverInfo.name().isBlank()) {
                return serverInfo.name();
            }
        } catch (Exception ignored) {
            // fall through to default name
        }
        return index == 0 ? "filesystem" : "mcp-server-" + (index + 1);
    }

    private JsonNode toJsonNode(Object schema) {
        if (schema == null) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.valueToTree(schema);
    }
}

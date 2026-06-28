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
    private volatile boolean clientsInitialized;

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
        log.info("MCP reconnect requested — re-initializing server connections");
        cachedSnapshot = null;
        clientsInitialized = false;
        return getToolsSnapshot(true);
    }

    public void logStartupSummary() {
        initializeClientsQuietly();
        McpToolsResponse snapshot = refreshToolsSnapshot();
        if (snapshot.connected()) {
            log.info(
                    "MCP ready: {} server(s), {} tool(s), sandbox={}",
                    snapshot.servers().size(),
                    snapshot.toolCount(),
                    snapshot.sandboxPath());
            snapshot.servers().forEach(server -> log.info("  MCP server online: {}", server));
        } else {
            log.warn("MCP is not connected: {}", snapshot.message());
        }
    }

    private McpToolsResponse getToolsSnapshot(boolean useCache) {
        if (useCache && cachedSnapshot != null) {
            return cachedSnapshot;
        }

        initializeClientsQuietly();
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

    private void initializeClientsQuietly() {
        if (clientsInitialized) {
            return;
        }
        List<McpSyncClient> clients = mcpClientsProvider.getIfAvailable();
        if (clients == null) {
            return;
        }
        synchronized (this) {
            if (clientsInitialized) {
                return;
            }
            for (int index = 0; index < clients.size(); index++) {
                McpSyncClient client = clients.get(index);
                try {
                    client.initialize();
                    logServerConnected(client, index);
                } catch (Exception exception) {
                    log.warn("MCP client #{} initialize failed: {}", index + 1, exception.getMessage());
                }
            }
            clientsInitialized = true;
        }
    }

    private void logServerConnected(McpSyncClient client, int index) {
        String serverName = resolveServerName(client, index);
        String version = "?";
        try {
            McpSchema.Implementation info = client.getServerInfo();
            if (info != null && info.version() != null) {
                version = info.version();
            }
        } catch (Exception ignored) {
            // keep default version
        }
        log.info("MCP server connected: name={}, version={}", serverName, version);
        try {
            McpSchema.ListToolsResult toolsResult = client.listTools();
            int toolCount = toolsResult != null && toolsResult.tools() != null
                    ? toolsResult.tools().size()
                    : 0;
            log.info("MCP server {} registered {} tool(s)", serverName, toolCount);
            if (toolsResult != null && toolsResult.tools() != null) {
                int limit = "mcp-study".equalsIgnoreCase(serverName) ? 10 : 4;
                toolsResult.tools().stream()
                        .limit(limit)
                        .forEach(tool -> log.info("  [{}] {}", serverName, tool.name()));
                if (toolCount > limit) {
                    log.info("  [{}] ... and {} more", serverName, toolCount - limit);
                }
            }
        } catch (Exception exception) {
            log.warn("MCP server {} tool list failed: {}", serverName, exception.getMessage());
        }
    }
}

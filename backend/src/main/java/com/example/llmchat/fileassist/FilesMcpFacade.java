package com.example.llmchat.fileassist;

import com.example.llmchat.config.AgentChatClientConfig;
import com.example.llmchat.dto.McpToolCallLogDto;
import com.example.llmchat.mcp.McpTextEncoding;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class FilesMcpFacade {

    private static final Logger log = LoggerFactory.getLogger(FilesMcpFacade.class);
    public static final String SERVER_NAME = AgentChatClientConfig.FILES_SERVER_NAME;

    private final ObjectProvider<List<McpSyncClient>> mcpClientsProvider;
    private final ObjectMapper objectMapper;

    public FilesMcpFacade(ObjectProvider<List<McpSyncClient>> mcpClientsProvider, ObjectMapper objectMapper) {
        this.mcpClientsProvider = mcpClientsProvider;
        this.objectMapper = objectMapper;
    }

    public ToolResult listFiles(String prefix, int limit) {
        return callTool("listFiles", Map.of(
                "prefix", prefix != null ? prefix : "",
                "limit", limit));
    }

    private ToolResult callTool(String toolName, Map<String, Object> args) {
        long start = System.currentTimeMillis();
        String argsJson;
        try {
            argsJson = objectMapper.writeValueAsString(args);
        } catch (Exception exception) {
            argsJson = String.valueOf(args);
        }
        try {
            McpSyncClient client = findFilesClient();
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(toolName, args));
            long duration = System.currentTimeMillis() - start;
            String text = extractText(result);
            boolean error = result.isError() != null && result.isError();
            McpToolCallLogDto logDto = new McpToolCallLogDto(
                    SERVER_NAME, toolName, argsJson, preview(text), duration);
            if (error) {
                return new ToolResult(false, text, logDto);
            }
            return new ToolResult(true, text, logDto);
        } catch (Exception exception) {
            long duration = System.currentTimeMillis() - start;
            String message = exception.getMessage() != null ? exception.getMessage() : String.valueOf(exception);
            log.warn("mcp-files.{} failed: {}", toolName, message);
            McpToolCallLogDto logDto = new McpToolCallLogDto(
                    SERVER_NAME, toolName, argsJson, preview(message), duration);
            return new ToolResult(false, message, logDto);
        }
    }

    private McpSyncClient findFilesClient() {
        List<McpSyncClient> clients = mcpClientsProvider.getIfAvailable();
        if (clients == null || clients.isEmpty()) {
            throw new IllegalStateException("MCP clients not available — enable app.mcp.enabled and restart");
        }
        for (McpSyncClient client : clients) {
            String name = resolveServerName(client);
            if (SERVER_NAME.equalsIgnoreCase(name) || "files".equalsIgnoreCase(name)) {
                try {
                    client.initialize();
                } catch (Exception ignored) {
                    // already initialized
                }
                return client;
            }
        }
        throw new IllegalStateException(
                "mcp-files not connected — run mvn -pl mcp-servers/mcp-files -am package and restart backend");
    }

    private String resolveServerName(McpSyncClient client) {
        try {
            McpSchema.Implementation info = client.getServerInfo();
            if (info != null && info.name() != null && !info.name().isBlank()) {
                return info.name();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "";
    }

    private String extractText(McpSchema.CallToolResult result) {
        if (result == null || result.content() == null) {
            return "{}";
        }
        StringBuilder builder = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent textContent) {
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(textContent.text());
            }
        }
        return McpTextEncoding.normalizeJson(builder.toString());
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= 240 ? trimmed : trimmed.substring(0, 240) + "…";
    }

    public record ToolResult(boolean ok, String text, McpToolCallLogDto toolCall) {
    }
}

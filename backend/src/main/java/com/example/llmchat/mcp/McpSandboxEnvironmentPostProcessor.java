package com.example.llmchat.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Prepares MCP sandbox directory and generates Claude Desktop-style stdio config for the filesystem server.
 */
public class McpSandboxEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String SANDBOX_ABSOLUTE_KEY = "app.mcp.sandbox.absolute";
    private static final String SANDBOX_RELATIVE_KEY = "app.mcp.sandbox.relative-path";
    private static final String SERVERS_CONFIG_KEY = "app.mcp.servers-config";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String relativePath = environment.getProperty(SANDBOX_RELATIVE_KEY, "data/mcp-sandbox");
        Path sandbox = Paths.get(relativePath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(sandbox);
            Path readme = sandbox.resolve("readme.txt");
            if (!Files.exists(readme)) {
                Files.writeString(
                        readme,
                        """
                                MCP sandbox — Day 16

                                Эта папка доступна публичному MCP-серверу @modelcontextprotocol/server-filesystem.
                                """);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to prepare MCP sandbox directory: " + sandbox, exception);
        }

        Path configFile = sandbox.resolve("mcp-servers.generated.json");
        try {
            writeServersConfig(configFile, sandbox);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write MCP servers config: " + configFile, exception);
        }

        Map<String, Object> properties = new HashMap<>();
        properties.put(SANDBOX_ABSOLUTE_KEY, sandbox.toString().replace('\\', '/'));
        properties.put(SERVERS_CONFIG_KEY, configFile.toAbsolutePath().normalize().toString().replace('\\', '/'));

        environment.getPropertySources().addFirst(new MapPropertySource("mcpSandbox", properties));
    }

    private void writeServersConfig(Path configFile, Path sandbox) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode servers = objectMapper.createObjectNode();
        ObjectNode filesystem = objectMapper.createObjectNode();
        ArrayNode args = objectMapper.createArrayNode();

        String sandboxPath = sandbox.toAbsolutePath().normalize().toString();
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) {
            filesystem.put("command", "cmd");
            args.add("/c");
            args.add("npx");
            args.add("-y");
            args.add("@modelcontextprotocol/server-filesystem");
            args.add(sandboxPath);
        } else {
            filesystem.put("command", "npx");
            args.add("-y");
            args.add("@modelcontextprotocol/server-filesystem");
            args.add(sandboxPath);
        }

        filesystem.set("args", args);
        servers.set("filesystem", filesystem);
        root.set("mcpServers", servers);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(configFile.toFile(), root);
    }
}

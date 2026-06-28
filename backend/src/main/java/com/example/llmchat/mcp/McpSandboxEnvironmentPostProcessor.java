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
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Prepares MCP sandbox directory and generates Claude Desktop-style stdio config
 * for filesystem (Day 16) and mcp-study (Day 17) servers.
 */
public class McpSandboxEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String SANDBOX_ABSOLUTE_KEY = "app.mcp.sandbox.absolute";
    private static final String SANDBOX_RELATIVE_KEY = "app.mcp.sandbox.relative-path";
    private static final String SERVERS_CONFIG_KEY = "app.mcp.servers-config";
    private static final String STUDY_DB_ABSOLUTE_KEY = "app.mcp.study-db.absolute";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String relativePath = environment.getProperty(SANDBOX_RELATIVE_KEY, "data/mcp-sandbox");
        Path sandbox = Paths.get(relativePath).toAbsolutePath().normalize();
        Path studyDb = Paths.get("data/study-reference.db").toAbsolutePath().normalize();
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
            if (studyDb.getParent() != null) {
                Files.createDirectories(studyDb.getParent());
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to prepare MCP directories: " + sandbox, exception);
        }

        Path configFile = sandbox.resolve("mcp-servers.generated.json");
        StudyLaunch studyLaunch = resolveStudyLaunch();
        try {
            writeServersConfig(configFile, sandbox, studyDb, studyLaunch);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write MCP servers config: " + configFile, exception);
        }

        Map<String, Object> properties = new HashMap<>();
        properties.put(SANDBOX_ABSOLUTE_KEY, sandbox.toString().replace('\\', '/'));
        properties.put(SERVERS_CONFIG_KEY, configFile.toAbsolutePath().normalize().toString().replace('\\', '/'));
        properties.put(STUDY_DB_ABSOLUTE_KEY, studyDb.toString().replace('\\', '/'));

        environment.getPropertySources().addFirst(new MapPropertySource("mcpSandbox", properties));
    }

    private StudyLaunch resolveStudyLaunch() {
        Path[] moduleRoots = {
                Paths.get("..", "mcp-servers", "mcp-study"),
                Paths.get("mcp-servers", "mcp-study"),
        };
        for (Path moduleRoot : moduleRoots) {
            Path absoluteRoot = moduleRoot.toAbsolutePath().normalize();
            Path classes = absoluteRoot.resolve("target/classes");
            Path dependencyDir = absoluteRoot.resolve("target/dependency");
            if (Files.isDirectory(classes) && Files.isDirectory(dependencyDir)) {
                try {
                    String classpath = buildClasspath(classes, dependencyDir);
                    return new StudyLaunch(classpath, "com.example.mcp.study.StudyMcpApplication");
                } catch (IOException exception) {
                    return null;
                }
            }
            Path bootJar = absoluteRoot.resolve("target/mcp-study-0.0.1-SNAPSHOT.jar");
            if (Files.isRegularFile(bootJar)) {
                return new StudyLaunch(null, null, bootJar);
            }
        }
        return null;
    }

    private String buildClasspath(Path classes, Path dependencyDir) throws IOException {
        try (Stream<Path> jars = Files.list(dependencyDir)) {
            String dependencies = jars
                    .filter(path -> path.toString().endsWith(".jar"))
                    .map(path -> path.toAbsolutePath().normalize().toString())
                    .collect(Collectors.joining(isWindows() ? ";" : ":"));
            String separator = isWindows() ? ";" : ":";
            return classes.toAbsolutePath().normalize() + separator + dependencies;
        }
    }

    private void writeServersConfig(Path configFile, Path sandbox, Path studyDb, StudyLaunch studyLaunch)
            throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode servers = objectMapper.createObjectNode();

        servers.set("filesystem", buildFilesystemServer(sandbox));
        if (studyLaunch != null) {
            servers.set("study", buildStudyServer(studyDb, studyLaunch));
        }

        root.set("mcpServers", servers);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(configFile.toFile(), root);
    }

    private ObjectNode buildFilesystemServer(Path sandbox) {
        ObjectNode filesystem = objectMapper.createObjectNode();
        ArrayNode args = objectMapper.createArrayNode();
        String sandboxPath = sandbox.toAbsolutePath().normalize().toString();

        if (isWindows()) {
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
        return filesystem;
    }

    private ObjectNode buildStudyServer(Path studyDb, StudyLaunch studyLaunch) {
        ObjectNode study = objectMapper.createObjectNode();
        ArrayNode args = objectMapper.createArrayNode();
        study.put("command", "java");

        ArrayNode jvmArgs = objectMapper.createArrayNode();
        jvmArgs.add("-Dfile.encoding=UTF-8");
        jvmArgs.add("-Dsun.jnu.encoding=UTF-8");

        if (studyLaunch.jarPath() != null) {
            for (var flag : jvmArgs) {
                args.add(flag.asText());
            }
            args.add("-jar");
            args.add(studyLaunch.jarPath().toAbsolutePath().normalize().toString());
        } else {
            for (var flag : jvmArgs) {
                args.add(flag.asText());
            }
            args.add("-cp");
            args.add(studyLaunch.classpath());
            args.add(studyLaunch.mainClass());
        }

        ObjectNode env = objectMapper.createObjectNode();
        env.put("STUDY_DB_PATH", studyDb.toAbsolutePath().normalize().toString());
        env.put("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8");
        study.set("env", env);
        study.set("args", args);
        return study;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private record StudyLaunch(String classpath, String mainClass, Path jarPath) {
        StudyLaunch(String classpath, String mainClass) {
            this(classpath, mainClass, null);
        }
    }
}

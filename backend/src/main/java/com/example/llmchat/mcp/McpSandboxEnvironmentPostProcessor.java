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
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Prepares MCP sandbox directory and generates Claude Desktop-style stdio config
 * for filesystem, mcp-study, mcp-scheduler, mcp-pipeline, mcp-git, and mcp-tickets.
 */
public class McpSandboxEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String SANDBOX_ABSOLUTE_KEY = "app.mcp.sandbox.absolute";
    private static final String SANDBOX_RELATIVE_KEY = "app.mcp.sandbox.relative-path";
    private static final String SERVERS_CONFIG_KEY = "app.mcp.servers-config";
    private static final String STUDY_DB_ABSOLUTE_KEY = "app.mcp.study-db.absolute";
    private static final String SCHEDULER_DB_ABSOLUTE_KEY = "app.mcp.scheduler-db.absolute";
    private static final String PIPELINE_OUTPUT_ABSOLUTE_KEY = "app.mcp.pipeline-output.absolute";
    private static final String GIT_REPO_ABSOLUTE_KEY = "app.mcp.git-repo.absolute";
    private static final String TICKETS_DIR_ABSOLUTE_KEY = "app.mcp.tickets-dir.absolute";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String relativePath = environment.getProperty(SANDBOX_RELATIVE_KEY, "data/mcp-sandbox");
        Path sandbox = Paths.get(relativePath).toAbsolutePath().normalize();
        Path studyDb = Paths.get("data/study-reference.db").toAbsolutePath().normalize();
        Path schedulerDb = Paths.get("data/scheduler.db").toAbsolutePath().normalize();
        Path pipelineOutput = Paths.get("data/pipeline").toAbsolutePath().normalize();
        Path gitRepoRoot = resolveGitRepoRoot();
        Path ticketsDir = Paths.get("data/tickets").toAbsolutePath().normalize();
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
            if (schedulerDb.getParent() != null) {
                Files.createDirectories(schedulerDb.getParent());
            }
            Files.createDirectories(pipelineOutput);
            Files.createDirectories(ticketsDir);
            // Always refresh seed tickets from repo so Cyrillic JSON stays UTF-8 correct.
            seedTicketsFromRepo(gitRepoRoot, ticketsDir);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to prepare MCP directories: " + sandbox, exception);
        }

        Path configFile = sandbox.resolve("mcp-servers.generated.json");
        ModuleLaunch studyLaunch = resolveModuleLaunch("mcp-study", "com.example.mcp.study.StudyMcpApplication");
        ModuleLaunch schedulerLaunch =
                resolveModuleLaunch("mcp-scheduler", "com.example.mcp.scheduler.SchedulerMcpApplication");
        ModuleLaunch pipelineLaunch =
                resolveModuleLaunch("mcp-pipeline", "com.example.mcp.pipeline.PipelineMcpApplication");
        ModuleLaunch gitLaunch = resolveModuleLaunch("mcp-git", "com.example.mcp.git.GitMcpApplication");
        ModuleLaunch ticketsLaunch =
                resolveModuleLaunch("mcp-tickets", "com.example.mcp.tickets.TicketsMcpApplication");
        try {
            writeServersConfig(
                    configFile,
                    sandbox,
                    studyDb,
                    schedulerDb,
                    pipelineOutput,
                    gitRepoRoot,
                    ticketsDir,
                    studyLaunch,
                    schedulerLaunch,
                    pipelineLaunch,
                    gitLaunch,
                    ticketsLaunch);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write MCP servers config: " + configFile, exception);
        }

        Map<String, Object> properties = new HashMap<>();
        properties.put(SANDBOX_ABSOLUTE_KEY, sandbox.toString().replace('\\', '/'));
        properties.put(SERVERS_CONFIG_KEY, configFile.toAbsolutePath().normalize().toString().replace('\\', '/'));
        properties.put(STUDY_DB_ABSOLUTE_KEY, studyDb.toString().replace('\\', '/'));
        properties.put(SCHEDULER_DB_ABSOLUTE_KEY, schedulerDb.toString().replace('\\', '/'));
        properties.put(PIPELINE_OUTPUT_ABSOLUTE_KEY, pipelineOutput.toString().replace('\\', '/'));
        properties.put(GIT_REPO_ABSOLUTE_KEY, gitRepoRoot.toString().replace('\\', '/'));
        properties.put(TICKETS_DIR_ABSOLUTE_KEY, ticketsDir.toString().replace('\\', '/'));

        environment.getPropertySources().addFirst(new MapPropertySource("mcpSandbox", properties));
    }

    private void seedTicketsFromRepo(Path repoRoot, Path ticketsDir) throws IOException {
        Path seedDir = repoRoot.resolve("support/tickets");
        if (!Files.isDirectory(seedDir)) {
            return;
        }
        try (Stream<Path> seeds = Files.list(seedDir)) {
            seeds.filter(path -> path.getFileName().toString().toLowerCase().endsWith(".json"))
                    .forEach(path -> {
                        try {
                            Files.copy(
                                    path,
                                    ticketsDir.resolve(path.getFileName()),
                                    StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException ignored) {
                            // best-effort seed
                        }
                    });
        }
    }

    private Path resolveGitRepoRoot() {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        Path[] candidates = {
                cwd,
                cwd.getParent(),
                Paths.get("..").toAbsolutePath().normalize()
        };
        for (Path candidate : candidates) {
            if (candidate != null && Files.isDirectory(candidate.resolve(".git"))) {
                return candidate;
            }
            if (candidate != null
                    && Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isDirectory(candidate.resolve("backend"))
                    && Files.isDirectory(candidate.resolve("mcp-servers"))) {
                return candidate;
            }
        }
        Path parent = cwd.getParent();
        return parent != null ? parent : cwd;
    }

    private ModuleLaunch resolveModuleLaunch(String moduleName, String mainClass) {
        Path[] moduleRoots = {
                Paths.get("..", "mcp-servers", moduleName),
                Paths.get("mcp-servers", moduleName),
        };
        for (Path moduleRoot : moduleRoots) {
            Path absoluteRoot = moduleRoot.toAbsolutePath().normalize();
            Path classes = absoluteRoot.resolve("target/classes");
            Path dependencyDir = absoluteRoot.resolve("target/dependency");
            if (Files.isDirectory(classes) && Files.isDirectory(dependencyDir)) {
                try {
                    String classpath = buildClasspath(classes, dependencyDir);
                    return new ModuleLaunch(classpath, mainClass);
                } catch (IOException exception) {
                    return null;
                }
            }
            Path bootJar = absoluteRoot.resolve("target/" + moduleName + "-0.0.1-SNAPSHOT.jar");
            if (Files.isRegularFile(bootJar)) {
                return new ModuleLaunch(null, null, bootJar);
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

    private void writeServersConfig(
            Path configFile,
            Path sandbox,
            Path studyDb,
            Path schedulerDb,
            Path pipelineOutput,
            Path gitRepoRoot,
            Path ticketsDir,
            ModuleLaunch studyLaunch,
            ModuleLaunch schedulerLaunch,
            ModuleLaunch pipelineLaunch,
            ModuleLaunch gitLaunch,
            ModuleLaunch ticketsLaunch)
            throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode servers = objectMapper.createObjectNode();

        servers.set("filesystem", buildFilesystemServer(sandbox));
        if (studyLaunch != null) {
            servers.set("study", buildJavaStdioServer(studyDb, studyLaunch, "STUDY_DB_PATH"));
        }
        if (schedulerLaunch != null) {
            servers.set("scheduler", buildJavaStdioServer(schedulerDb, schedulerLaunch, "SCHEDULER_DB_PATH"));
        }
        if (pipelineLaunch != null) {
            servers.set("pipeline", buildDirEnvStdioServer(pipelineOutput, pipelineLaunch, "PIPELINE_OUTPUT_DIR"));
        }
        if (gitLaunch != null) {
            servers.set("git", buildDirEnvStdioServer(gitRepoRoot, gitLaunch, "GIT_REPO_ROOT"));
        }
        if (ticketsLaunch != null) {
            servers.set("tickets", buildDirEnvStdioServer(ticketsDir, ticketsLaunch, "TICKETS_DIR"));
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

    private ObjectNode buildJavaStdioServer(Path dbPath, ModuleLaunch launch, String envKey) {
        return buildDirEnvStdioServer(dbPath, launch, envKey);
    }

    private ObjectNode buildDirEnvStdioServer(Path dirOrFile, ModuleLaunch launch, String envKey) {
        ObjectNode server = objectMapper.createObjectNode();
        ArrayNode args = objectMapper.createArrayNode();
        server.put("command", "java");

        ArrayNode jvmArgs = objectMapper.createArrayNode();
        jvmArgs.add("-Dfile.encoding=UTF-8");
        jvmArgs.add("-Dsun.jnu.encoding=UTF-8");

        if (launch.jarPath() != null) {
            for (var flag : jvmArgs) {
                args.add(flag.asText());
            }
            args.add("-jar");
            args.add(launch.jarPath().toAbsolutePath().normalize().toString());
        } else {
            for (var flag : jvmArgs) {
                args.add(flag.asText());
            }
            args.add("-cp");
            args.add(launch.classpath());
            args.add(launch.mainClass());
        }

        ObjectNode env = objectMapper.createObjectNode();
        env.put(envKey, dirOrFile.toAbsolutePath().normalize().toString());
        env.put("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8");
        server.set("env", env);
        server.set("args", args);
        return server;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private record ModuleLaunch(String classpath, String mainClass, Path jarPath) {
        ModuleLaunch(String classpath, String mainClass) {
            this(classpath, mainClass, null);
        }
    }
}

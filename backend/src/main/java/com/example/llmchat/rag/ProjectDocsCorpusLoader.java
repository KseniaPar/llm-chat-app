package com.example.llmchat.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Loads project documentation sources (README + docs folders) for developer RAG.
 */
@Component
public class ProjectDocsCorpusLoader {

    private static final Logger log = LoggerFactory.getLogger(ProjectDocsCorpusLoader.class);

    private final List<String> configuredSources;
    private final Path repoRoot;

    public ProjectDocsCorpusLoader(
            @Value("${app.rag.project-sources:../README.md,../project/docs}") String configuredSources) {
        this.configuredSources = parseSources(configuredSources);
        this.repoRoot = resolveRepoRoot();
    }

    private static List<String> parseSources(String configuredSources) {
        if (configuredSources == null || configuredSources.isBlank()) {
            return List.of("../README.md", "../project/docs");
        }
        String trimmed = configuredSources.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        List<String> parts = new ArrayList<>();
        for (String part : trimmed.split(",")) {
            String item = part.trim().replace("\"", "").replace("'", "");
            if (!item.isEmpty()) {
                parts.add(item);
            }
        }
        return parts.isEmpty() ? List.of("../README.md", "../project/docs") : List.copyOf(parts);
    }

    public Path repoRoot() {
        return repoRoot;
    }

    public List<RagDocument> loadAll() {
        Set<Path> files = new LinkedHashSet<>();
        for (String raw : configuredSources) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            Path resolved = resolveSource(raw.trim());
            if (Files.isRegularFile(resolved)) {
                if (isSupported(resolved)) {
                    files.add(resolved.normalize());
                }
            } else if (Files.isDirectory(resolved)) {
                try (Stream<Path> walk = Files.walk(resolved)) {
                    walk.filter(Files::isRegularFile)
                            .filter(this::isSupported)
                            .sorted()
                            .forEach(path -> files.add(path.normalize()));
                } catch (IOException exception) {
                    log.warn("Failed to scan project docs dir {}: {}", resolved, exception.getMessage());
                }
            } else {
                log.warn("Project RAG source not found: {} (resolved {})", raw, resolved);
            }
        }

        List<RagDocument> documents = new ArrayList<>();
        for (Path path : files) {
            loadDocument(path).ifPresent(documents::add);
        }
        log.info("Loaded {} project docs from {} source(s) under {}", documents.size(), configuredSources.size(), repoRoot);
        return documents;
    }

    public CorpusStats stats() {
        List<RagDocument> docs = loadAll();
        int totalChars = docs.stream().mapToInt(d -> d.content().length()).sum();
        int estimatedPages = Math.max(1, totalChars / 3000);
        return new CorpusStats(docs.size(), totalChars, estimatedPages);
    }

    private Optional<RagDocument> loadDocument(Path path) {
        try {
            String relative = relativize(path);
            String title = path.getFileName().toString();
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                log.warn("Empty project doc: {}", path);
                return Optional.empty();
            }
            log.info("Loaded project doc {} — {} chars", relative, content.length());
            return Optional.of(new RagDocument(relative, title, "md", content));
        } catch (Exception exception) {
            log.warn("Failed to read project doc {}: {}", path, exception.getMessage());
            return Optional.empty();
        }
    }

    private Path resolveSource(String raw) {
        Path path = Paths.get(raw);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        Path fromCwd = Paths.get(raw).toAbsolutePath().normalize();
        if (Files.exists(fromCwd)) {
            return fromCwd;
        }
        return repoRoot.resolve(raw.replaceFirst("^\\.\\./", "")).normalize();
    }

    private String relativize(Path path) {
        try {
            if (path.startsWith(repoRoot)) {
                return repoRoot.relativize(path).toString().replace('\\', '/');
            }
        } catch (Exception ignored) {
            // fall through
        }
        return path.getFileName().toString();
    }

    private boolean isSupported(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".md") || name.endsWith(".txt");
    }

    private static Path resolveRepoRoot() {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        Path[] candidates = {
                cwd,
                cwd.getParent(),
                Paths.get("..").toAbsolutePath().normalize(),
                Paths.get(".").toAbsolutePath().normalize()
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

    public record CorpusStats(int documentCount, int totalChars, int estimatedPages) {
    }
}

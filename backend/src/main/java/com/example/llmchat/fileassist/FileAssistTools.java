package com.example.llmchat.fileassist;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/** In-process file tools for Day 34 (fast boot, no MCP STDIO). */
@Component
public class FileAssistTools {

    public static final String TOOL_SERVER_NAME = "file-tools";

    private static final Logger log = LoggerFactory.getLogger(FileAssistTools.class);
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_READ_CHARS = 120_000;
    private static final int MAX_MATCHES = 80;

    private final FilePathGuard pathGuard;
    private final ObjectMapper objectMapper;

    public FileAssistTools(FilePathGuard pathGuard, ObjectMapper objectMapper) {
        this.pathGuard = pathGuard;
        this.objectMapper = objectMapper;
        log.info("FileAssist tools repo root: {}", pathGuard.repoRoot());
    }

    @Tool(description = "Read a text file from the project repository. Returns JSON.")
    public String readFile(@ToolParam(description = "Relative path") String path) {
        Path file = pathGuard.resolveReadable(path);
        if (!Files.isRegularFile(file)) {
            return toJson(Map.of("found", false, "path", path, "message", "Not a regular file"));
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            boolean truncated = content.length() > MAX_READ_CHARS;
            if (truncated) {
                content = content.substring(0, MAX_READ_CHARS);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("found", true);
            result.put("path", rel(file));
            result.put("size", Files.size(file));
            result.put("truncated", truncated);
            result.put("content", content);
            return toJson(result);
        } catch (IOException exception) {
            throw new IllegalStateException("readFile failed: " + exception.getMessage(), exception);
        }
    }

    @Tool(description = "List files under directory prefix. Returns JSON.")
    public String listFiles(
            @ToolParam(description = "Directory prefix", required = false) String prefix,
            @ToolParam(description = "Max files", required = false) Integer limit) {
        int max = limit != null && limit > 0 ? Math.min(limit, 500) : DEFAULT_LIMIT;
        String prefixNorm = normalizePrefix(prefix);
        Path start = prefixNorm.isBlank() ? pathGuard.repoRoot() : pathGuard.resolveReadable(prefixNorm);
        if (!Files.exists(start)) {
            return toJson(Map.of("prefix", prefixNorm, "count", 0, "files", List.of()));
        }
        List<String> files = new ArrayList<>();
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (".git".equals(name) || "target".equals(name) || "node_modules".equals(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (files.size() >= max) {
                        return FileVisitResult.TERMINATE;
                    }
                    try {
                        files.add(rel(file));
                    } catch (Exception ignored) {
                        // skip
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException("listFiles failed: " + exception.getMessage(), exception);
        }
        files.sort(Comparator.naturalOrder());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("prefix", prefixNorm);
        result.put("count", files.size());
        result.put("files", files);
        return toJson(result);
    }

    @Tool(description = "Search regex/literal across project files. Returns JSON.")
    public String searchFiles(
            @ToolParam(description = "Query") String query,
            @ToolParam(description = "Optional glob", required = false) String glob,
            @ToolParam(description = "Max matches", required = false) Integer limit) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query is required");
        }
        int max = limit != null && limit > 0 ? Math.min(limit, MAX_MATCHES) : DEFAULT_LIMIT;
        Pattern pattern = compileQuery(query.trim());
        String globNorm = glob != null ? glob.trim().replace('\\', '/') : "";
        List<Map<String, Object>> matches = new ArrayList<>();
        try {
            Files.walkFileTree(pathGuard.repoRoot(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (".git".equals(name) || "target".equals(name) || "node_modules".equals(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matches.size() >= max) {
                        return FileVisitResult.TERMINATE;
                    }
                    String relative = rel(file);
                    try {
                        pathGuard.resolveReadable(relative);
                    } catch (Exception exception) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!isTextCandidate(relative) || (!globNorm.isBlank() && !matchesGlob(relative, globNorm))) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                        for (int i = 0; i < lines.size(); i++) {
                            if (matches.size() >= max) {
                                break;
                            }
                            if (pattern.matcher(lines.get(i)).find()) {
                                Map<String, Object> hit = new LinkedHashMap<>();
                                hit.put("path", relative);
                                hit.put("line", i + 1);
                                hit.put("snippet", truncate(lines.get(i).trim(), 240));
                                matches.add(hit);
                            }
                        }
                    } catch (IOException ignored) {
                        // skip
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException("searchFiles failed: " + exception.getMessage(), exception);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("glob", globNorm);
        result.put("count", matches.size());
        result.put("matches", matches);
        return toJson(result);
    }

    @Tool(description = "Write file to allowlisted path. Returns JSON with unifiedDiff.")
    public String writeFile(
            @ToolParam(description = "Relative path") String path,
            @ToolParam(description = "Content") String content,
            @ToolParam(description = "Dry run", required = false) Boolean dryRun) {
        if (content == null) {
            throw new IllegalArgumentException("content is required");
        }
        Path file = pathGuard.resolveWritable(path);
        boolean preview = dryRun != null && dryRun;
        String oldContent = "";
        boolean created = !Files.exists(file);
        if (!created) {
            try {
                oldContent = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new IllegalStateException(exception.getMessage(), exception);
            }
        }
        String relative = rel(file);
        String unifiedDiff = UnifiedDiff.build(relative, oldContent, content);
        if (!preview) {
            try {
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.writeString(file, content, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new IllegalStateException("writeFile failed: " + exception.getMessage(), exception);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("written", !preview);
        result.put("dryRun", preview);
        result.put("path", relative);
        result.put("created", created);
        result.put("bytesWritten", content.getBytes(StandardCharsets.UTF_8).length);
        result.put("unifiedDiff", unifiedDiff);
        return toJson(result);
    }

    private String rel(Path file) {
        return pathGuard.repoRoot().relativize(file).toString().replace('\\', '/');
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }

    private static Pattern compileQuery(String query) {
        try {
            return Pattern.compile(query, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
        } catch (PatternSyntaxException exception) {
            return Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
        }
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null) {
            return "";
        }
        String value = prefix.trim().replace('\\', '/');
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        if (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }

    private static boolean isTextCandidate(String relative) {
        String lower = relative.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java") || lower.endsWith(".md") || lower.endsWith(".yml")
                || lower.endsWith(".yaml") || lower.endsWith(".json") || lower.endsWith(".xml")
                || lower.endsWith(".html") || lower.endsWith(".js") || lower.endsWith(".css")
                || lower.endsWith(".txt") || lower.endsWith(".properties") || lower.endsWith(".ts")
                || lower.endsWith(".tsx");
    }

    private static boolean matchesGlob(String path, String glob) {
        return Pattern.compile(globToRegex(glob), Pattern.CASE_INSENSITIVE).matcher(path).matches();
    }

    private static String globToRegex(String glob) {
        StringBuilder builder = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char ch = glob.charAt(i);
            if (ch == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    builder.append(".*");
                    i++;
                } else {
                    builder.append("[^/]*");
                }
            } else if (".[]{}()+-^$|".indexOf(ch) >= 0) {
                builder.append('\\').append(ch);
            } else {
                builder.append(ch);
            }
        }
        return builder.append('$').toString();
    }

    private static String truncate(String text, int max) {
        return text == null || text.length() <= max ? (text == null ? "" : text) : text.substring(0, max) + "…";
    }
}

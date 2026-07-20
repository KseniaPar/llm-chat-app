package com.example.mcp.files;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

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

/**
 * Day 34 project file tools. Returns JSON strings with escaped non-ASCII for STDIO on Windows.
 */
@Service
public class ProjectFileTools {

    private static final Logger log = LoggerFactory.getLogger(ProjectFileTools.class);
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_READ_CHARS = 120_000;
    private static final int MAX_MATCHES = 80;

    private final FilePathGuard pathGuard;
    private final ObjectMapper wireMapper;

    public ProjectFileTools(FilePathGuard pathGuard) {
        this.pathGuard = pathGuard;
        this.wireMapper = JsonMapper.builder()
                .enable(JsonWriteFeature.ESCAPE_NON_ASCII)
                .build();
        log.info("mcp-files repo root: {}", pathGuard.repoRoot());
    }

    @Tool(description = """
            Read a text file from the project repository (relative path, e.g. backend/src/... or project/docs/...).
            Returns JSON with path, size, and content (truncated if very large).""")
    public String readFile(
            @ToolParam(description = "Relative path inside repo") String path) {
        Path file = pathGuard.resolveReadable(path);
        if (!Files.isRegularFile(file)) {
            return toWireJson(Map.of(
                    "found", false,
                    "path", path,
                    "message", "Not a regular file"));
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            boolean truncated = content.length() > MAX_READ_CHARS;
            if (truncated) {
                content = content.substring(0, MAX_READ_CHARS);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("found", true);
            result.put("path", pathGuard.repoRoot().relativize(file).toString().replace('\\', '/'));
            result.put("size", Files.size(file));
            result.put("truncated", truncated);
            result.put("content", content);
            log.info("readFile {} ({} bytes, truncated={})", path, Files.size(file), truncated);
            return toWireJson(result);
        } catch (IOException exception) {
            throw new IllegalStateException("readFile failed: " + exception.getMessage(), exception);
        }
    }

    @Tool(description = """
            List files under a directory prefix (relative path, default '' = repo root).
            Skips .git, target, node_modules. Returns JSON with paths.""")
    public String listFiles(
            @ToolParam(description = "Directory prefix filter", required = false) String prefix,
            @ToolParam(description = "Max files (default 50)", required = false) Integer limit) {
        int max = limit != null && limit > 0 ? Math.min(limit, 500) : DEFAULT_LIMIT;
        String prefixNorm = normalizePrefix(prefix);
        Path start = prefixNorm.isBlank()
                ? pathGuard.repoRoot()
                : pathGuard.resolveReadable(prefixNorm);
        if (!Files.exists(start)) {
            return toWireJson(Map.of(
                    "prefix", prefixNorm,
                    "count", 0,
                    "files", List.of(),
                    "message", "Path does not exist"));
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
                        pathGuard.resolveReadable(
                                pathGuard.repoRoot().relativize(file).toString().replace('\\', '/'));
                        files.add(pathGuard.repoRoot().relativize(file).toString().replace('\\', '/'));
                    } catch (Exception ignored) {
                        // skip denied
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
        log.info("listFiles prefix='{}' -> {} file(s)", prefixNorm, files.size());
        return toWireJson(result);
    }

    @Tool(description = """
            Search for a regex or literal query across project text files.
            Optional glob filter like *.java or backend/**/*.java.
            Returns JSON matches with path, line number, and snippet.""")
    public String searchFiles(
            @ToolParam(description = "Regex or literal to search for") String query,
            @ToolParam(description = "Optional glob, e.g. backend/**/*.java", required = false) String glob,
            @ToolParam(description = "Max matches (default 50)", required = false) Integer limit) {
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
                    String relative = pathGuard.repoRoot().relativize(file).toString().replace('\\', '/');
                    try {
                        pathGuard.resolveReadable(relative);
                    } catch (Exception exception) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!isTextCandidate(relative)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!globNorm.isBlank() && !matchesGlob(relative, globNorm)) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                        for (int i = 0; i < lines.size(); i++) {
                            if (matches.size() >= max) {
                                break;
                            }
                            String line = lines.get(i);
                            if (pattern.matcher(line).find()) {
                                Map<String, Object> hit = new LinkedHashMap<>();
                                hit.put("path", relative);
                                hit.put("line", i + 1);
                                hit.put("snippet", truncate(line.trim(), 240));
                                matches.add(hit);
                            }
                        }
                    } catch (IOException ignored) {
                        // skip unreadable/binary
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
        log.info("searchFiles q='{}' glob='{}' -> {} hit(s)", query, globNorm, matches.size());
        return toWireJson(result);
    }

    @Tool(description = """
            Write or overwrite a file in the project repository.
            Writes only to allowlisted paths: project/docs/**, docs/**, adr/**, README.md, CHANGELOG.md.
            Set dryRun=true to preview unified diff without saving.
            Returns JSON with path, created, bytesWritten, unifiedDiff.""")
    public String writeFile(
            @ToolParam(description = "Relative path to write") String path,
            @ToolParam(description = "Full new file content") String content,
            @ToolParam(description = "Preview only, do not save", required = false) Boolean dryRun) {
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
                throw new IllegalStateException("Cannot read existing file: " + exception.getMessage(), exception);
            }
        }
        String relative = pathGuard.repoRoot().relativize(file).toString().replace('\\', '/');
        String unifiedDiff = buildUnifiedDiff(relative, oldContent, content);
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
        log.info("writeFile {} created={} dryRun={}", relative, created, preview);
        return toWireJson(result);
    }

    private String toWireJson(Object value) {
        try {
            return wireMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("JSON serialize failed: " + exception.getMessage(), exception);
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
        return lower.endsWith(".java")
                || lower.endsWith(".md")
                || lower.endsWith(".yml")
                || lower.endsWith(".yaml")
                || lower.endsWith(".json")
                || lower.endsWith(".xml")
                || lower.endsWith(".html")
                || lower.endsWith(".js")
                || lower.endsWith(".css")
                || lower.endsWith(".txt")
                || lower.endsWith(".properties")
                || lower.endsWith(".gradle")
                || lower.endsWith(".sh")
                || lower.endsWith(".mjs")
                || lower.endsWith(".ts")
                || lower.endsWith(".tsx");
    }

    private static boolean matchesGlob(String path, String glob) {
        String regex = globToRegex(glob);
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(path).matches();
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
            } else if (ch == '?') {
                builder.append("[^/]");
            } else if (".[]{}()+-^$|".indexOf(ch) >= 0) {
                builder.append('\\').append(ch);
            } else {
                builder.append(ch);
            }
        }
        builder.append('$');
        return builder.toString();
    }

    static String buildUnifiedDiff(String path, String oldContent, String newContent) {
        List<String> oldLines = splitLines(oldContent);
        List<String> newLines = splitLines(newContent);
        StringBuilder diff = new StringBuilder();
        diff.append("--- a/").append(path).append('\n');
        diff.append("+++ b/").append(path).append('\n');
        int max = Math.max(oldLines.size(), newLines.size());
        int contextStart = -1;
        for (int i = 0; i < max; i++) {
            String oldLine = i < oldLines.size() ? oldLines.get(i) : null;
            String newLine = i < newLines.size() ? newLines.get(i) : null;
            boolean changed = !java.util.Objects.equals(oldLine, newLine);
            if (changed) {
                if (contextStart < 0) {
                    contextStart = Math.max(0, i - 2);
                    int oldCount = 0;
                    int newCount = 0;
                    for (int j = contextStart; j < max; j++) {
                        String o = j < oldLines.size() ? oldLines.get(j) : null;
                        String n = j < newLines.size() ? newLines.get(j) : null;
                        if (!java.util.Objects.equals(o, n)) {
                            oldCount = j - contextStart + 1;
                            newCount = j - contextStart + 1;
                        } else if (j > i) {
                            break;
                        }
                    }
                    diff.append("@@ -").append(contextStart + 1).append(',').append(oldCount)
                            .append(" +").append(contextStart + 1).append(',').append(newCount)
                            .append(" @@\n");
                }
                if (oldLine != null) {
                    diff.append('-').append(oldLine).append('\n');
                }
                if (newLine != null) {
                    diff.append('+').append(newLine).append('\n');
                }
            }
        }
        if (diff.length() == ("--- a/" + path + "\n+++ b/" + path + "\n").length()) {
            diff.append("@@ no changes @@\n");
        }
        return diff.toString();
    }

    private static List<String> splitLines(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        return content.lines().collect(Collectors.toList());
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}

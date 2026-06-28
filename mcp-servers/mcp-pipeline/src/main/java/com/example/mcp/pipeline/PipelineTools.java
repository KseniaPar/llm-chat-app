package com.example.mcp.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class PipelineTools {

    private static final Logger log = LoggerFactory.getLogger(PipelineTools.class);
    private static final TypeReference<List<Map<String, Object>>> ITEM_LIST_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final Path outputDir;

    public PipelineTools(
            ObjectMapper objectMapper,
            @Value("${pipeline.output.dir:data/pipeline}") String outputDirPath) {
        this.objectMapper = objectMapper;
        this.outputDir = Paths.get(outputDirPath).toAbsolutePath().normalize();
    }

    @Tool(description = """
            Search the static study corpus by keyword. Returns matching items with title, snippet, and url.
            First step of the pipeline — use before summarize.""")
    public Map<String, Object> search(
            @ToolParam(description = "Search query, e.g. 'пять столпов ислама'") String query) {
        String normalized = McpEncodingFix.normalize(query);
        List<Map<String, Object>> items = PipelineCorpus.search(normalized != null ? normalized : "");
        log.info("search query='{}' -> {} item(s)", normalized, items.size());
        List<Map<String, Object>> encodedItems = items.stream()
                .map(item -> Map.<String, Object>of(
                        "title", McpEncodingFix.normalize(String.valueOf(item.get("title"))),
                        "snippet", McpEncodingFix.normalize(String.valueOf(item.get("snippet"))),
                        "url", item.get("url")))
                .toList();
        return Map.of(
                "query", normalized != null ? normalized : "",
                "itemCount", encodedItems.size(),
                "items", encodedItems);
    }

    @Tool(description = """
            Summarize search items into a short overview and key points.
            Second step — pass itemsJson from search result (JSON array).""")
    public Map<String, Object> summarize(
            @ToolParam(description = "JSON array of items from search, e.g. [{title, snippet, url}]") String itemsJson) {
        List<Map<String, Object>> items = parseItems(itemsJson);
        if (items.isEmpty()) {
            return Map.of(
                    "summary", "Нет данных для конспекта.",
                    "keyPoints", List.of(),
                    "sourceCount", 0);
        }

        StringBuilder summary = new StringBuilder();
        Set<String> keyPoints = new LinkedHashSet<>();
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);
            String title = McpEncodingFix.normalize(stringValue(item.get("title")));
            String snippet = McpEncodingFix.normalize(stringValue(item.get("snippet")));
            if (!title.isBlank()) {
                summary.append(i + 1).append(". ").append(title).append(" — ");
            }
            summary.append(snippet);
            if (!snippet.endsWith(".")) {
                summary.append('.');
            }
            summary.append('\n');
            if (!title.isBlank()) {
                keyPoints.add(title);
            }
            if (keyPoints.size() < 5 && !snippet.isBlank()) {
                String shortPoint = snippet.length() > 90 ? snippet.substring(0, 87) + "…" : snippet;
                keyPoints.add(shortPoint);
            }
        }

        List<String> points = new ArrayList<>(keyPoints);
        if (points.size() > 5) {
            points = points.subList(0, 5);
        }

        log.info("summarize {} item(s) -> {} key point(s)", items.size(), points.size());
        return Map.of(
                "summary", summary.toString().trim(),
                "keyPoints", points,
                "sourceCount", items.size());
    }

    @Tool(description = """
            Save summary text to a file in the pipeline output directory.
            Third step — pass summary from summarize and a safe filename.""")
    public Map<String, Object> saveToFile(
            @ToolParam(description = "Summary text to write") String summary,
            @ToolParam(description = "Filename, e.g. islam-pillars.txt") String filename) {
        String safeName = sanitizeFilename(filename);
        if (safeName.isBlank()) {
            safeName = "pipeline-output.txt";
        }
        String body = McpEncodingFix.normalize(summary != null ? summary : "");
        try {
            Files.createDirectories(outputDir);
            Path target = outputDir.resolve(safeName).normalize();
            if (!target.startsWith(outputDir)) {
                throw new IllegalArgumentException("Invalid filename");
            }
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.writeString(target, body, java.nio.charset.StandardCharsets.UTF_8);
            log.info("saveToFile {} -> {} bytes", safeName, bytes.length);
            return Map.of(
                    "path", target.toString(),
                    "filename", safeName,
                    "bytesWritten", bytes.length);
        } catch (Exception exception) {
            log.error("saveToFile failed: {}", exception.getMessage());
            return Map.of(
                    "path", "",
                    "filename", safeName,
                    "bytesWritten", 0,
                    "error", exception.getMessage());
        }
    }

    private List<Map<String, Object>> parseItems(String itemsJson) {
        if (itemsJson == null || itemsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(itemsJson, ITEM_LIST_TYPE);
        } catch (Exception exception) {
            log.warn("Failed to parse itemsJson: {}", exception.getMessage());
            return List.of();
        }
    }

    private static String sanitizeFilename(String filename) {
        if (filename == null) {
            return "pipeline-output.txt";
        }
        String trimmed = filename.trim().replace('\\', '/');
        int slash = trimmed.lastIndexOf('/');
        if (slash >= 0) {
            trimmed = trimmed.substring(slash + 1);
        }
        trimmed = trimmed.replaceAll("[^a-zA-Z0-9._\\-а-яА-ЯёЁ]", "-");
        if (trimmed.isBlank()) {
            return "pipeline-output.txt";
        }
        if (!trimmed.toLowerCase(Locale.ROOT).endsWith(".txt")) {
            trimmed = trimmed + ".txt";
        }
        return trimmed;
    }

    private static String stringValue(Object value) {
        return value != null ? value.toString().trim() : "";
    }
}

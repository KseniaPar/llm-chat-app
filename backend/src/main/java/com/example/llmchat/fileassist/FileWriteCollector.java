package com.example.llmchat.fileassist;

import com.example.llmchat.dto.FileWriteResultDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Captures full writeFile tool results (preview in McpToolCallLogDto may truncate diff).
 */
public final class FileWriteCollector {

    private static final ThreadLocal<List<String>> WRITES = ThreadLocal.withInitial(CopyOnWriteArrayList::new);

    private FileWriteCollector() {
    }

    public static void begin() {
        WRITES.get().clear();
    }

    public static void record(String json) {
        if (json != null && !json.isBlank()) {
            WRITES.get().add(json);
        }
    }

    public static List<FileWriteResultDto> drain(ObjectMapper objectMapper) {
        List<String> raw = List.copyOf(WRITES.get());
        WRITES.get().clear();
        List<FileWriteResultDto> results = new ArrayList<>();
        for (String json : raw) {
            try {
                JsonNode node = objectMapper.readTree(json);
                results.add(new FileWriteResultDto(
                        text(node, "path"),
                        node.path("created").asBoolean(false),
                        node.path("dryRun").asBoolean(false),
                        node.path("written").asBoolean(false),
                        text(node, "unifiedDiff")));
            } catch (Exception ignored) {
                // skip malformed
            }
        }
        return results;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }
}

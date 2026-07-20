package com.example.llmchat.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reads ticket JSON files as UTF-8 for the support UI (avoids STDIO mojibake on list).
 */
@Component
public class SupportTicketStore {

    private final Path ticketsDir;
    private final ObjectMapper objectMapper;

    public SupportTicketStore(
            @Value("${app.mcp.tickets-dir.absolute:}") String ticketsDirAbsolute,
            ObjectMapper objectMapper) {
        Path configured = ticketsDirAbsolute != null && !ticketsDirAbsolute.isBlank()
                ? Paths.get(ticketsDirAbsolute)
                : Paths.get("data/tickets");
        this.ticketsDir = configured.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    public Path ticketsDir() {
        return ticketsDir;
    }

    public ObjectNode listSummaries() {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode tickets = objectMapper.createArrayNode();
        try {
            Files.createDirectories(ticketsDir);
            try (Stream<Path> stream = Files.list(ticketsDir)) {
                stream.filter(path -> path.getFileName().toString().toLowerCase().endsWith(".json"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .forEach(path -> {
                            try {
                                JsonNode node = objectMapper.readTree(
                                        Files.readString(path, StandardCharsets.UTF_8));
                                ObjectNode summary = objectMapper.createObjectNode();
                                String status = normalizeStatus(text(node, "status"));
                                if (status.isBlank()) {
                                    status = "open";
                                }
                                summary.put("id", text(node, "id"));
                                summary.put("subject", text(node, "subject"));
                                summary.put("status", status);
                                summary.put("priority", text(node, "priority"));
                                summary.put("user", text(node, "user"));
                                summary.put("file", path.getFileName().toString());
                                summary.set("allowedNext", allowedNextArray(status));
                                tickets.add(summary);
                            } catch (Exception ignored) {
                                // skip bad file
                            }
                        });
            }
        } catch (Exception exception) {
            root.put("error", exception.getMessage());
        }
        root.put("count", tickets.size());
        root.set("tickets", tickets);
        root.put("ticketsDir", ticketsDir.toString());
        return root;
    }

    public ObjectNode updateStatus(String ticketId, String newStatus) {
        String next = normalizeStatus(newStatus);
        if (next.isBlank()) {
            throw new IllegalArgumentException("status must be open, in_progress, or resolved");
        }
        Path file = resolveTicketFile(ticketId);
        if (file == null) {
            throw new IllegalArgumentException("Ticket not found: " + ticketId);
        }
        try {
            ObjectNode node = (ObjectNode) objectMapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            String current = normalizeStatus(text(node, "status"));
            if (current.isBlank()) {
                current = "open";
            }
            if (!current.equals(next) && !ALLOWED_TRANSITIONS.getOrDefault(current, List.of()).contains(next)) {
                ObjectNode denied = objectMapper.createObjectNode();
                denied.put("updated", false);
                denied.put("id", text(node, "id"));
                denied.put("from", current);
                denied.put("to", next);
                denied.set("allowed", allowedNextArray(current));
                denied.put("message", "Transition not allowed: " + current + " → " + next);
                return denied;
            }
            node.put("status", next);
            node.put("updatedAt", Instant.now().toString());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), node);

            ObjectNode result = objectMapper.createObjectNode();
            result.put("updated", true);
            result.put("id", text(node, "id"));
            result.put("from", current);
            result.put("status", next);
            result.put("file", file.getFileName().toString());
            result.set("allowedNext", allowedNextArray(next));
            return result;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to update ticket status: " + exception.getMessage(), exception);
        }
    }

    private ArrayNode allowedNextArray(String status) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (String next : ALLOWED_TRANSITIONS.getOrDefault(status, List.of())) {
            arr.add(next);
        }
        return arr;
    }

    private Path resolveTicketFile(String ticketId) {
        if (ticketId == null || ticketId.isBlank()) {
            return null;
        }
        String raw = ticketId.trim();
        if (raw.toLowerCase().endsWith(".json")) {
            Path direct = ticketsDir.resolve(raw).normalize();
            if (direct.startsWith(ticketsDir) && Files.isRegularFile(direct)) {
                return direct;
            }
        }
        Path byId = ticketsDir.resolve(raw + ".json").normalize();
        if (byId.startsWith(ticketsDir) && Files.isRegularFile(byId)) {
            return byId;
        }
        try (Stream<Path> stream = Files.list(ticketsDir)) {
            return stream
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".json"))
                    .filter(path -> {
                        try {
                            JsonNode node = objectMapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
                            return raw.equalsIgnoreCase(text(node, "id"));
                        } catch (Exception exception) {
                            return false;
                        }
                    })
                    .findFirst()
                    .orElse(null);
        } catch (Exception exception) {
            return null;
        }
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "";
        }
        String value = status.trim().toLowerCase().replace('-', '_').replace(' ', '_');
        if ("inprogress".equals(value)) {
            value = "in_progress";
        }
        return ALLOWED_TRANSITIONS.containsKey(value) ? value : "";
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.asText("");
    }

    private static final Map<String, List<String>> ALLOWED_TRANSITIONS = Map.of(
            "open", List.of("in_progress", "resolved"),
            "in_progress", List.of("open", "resolved"),
            "resolved", List.of("open", "in_progress"));
}

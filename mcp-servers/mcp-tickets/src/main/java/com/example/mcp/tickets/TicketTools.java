package com.example.mcp.tickets;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Ticket tools return JSON {@link String} with non-ASCII escaped so STDIO on Windows
 * cannot corrupt Cyrillic.
 */
@Service
public class TicketTools {

    private static final Logger log = LoggerFactory.getLogger(TicketTools.class);

    private final Path ticketsDir;
    private final ObjectMapper objectMapper;
    private final ObjectMapper wireMapper;

    public TicketTools(
            @Value("${tickets.dir:data/tickets}") String ticketsDirProperty,
            ObjectMapper objectMapper) {
        this.ticketsDir = resolveTicketsDir(ticketsDirProperty);
        this.objectMapper = objectMapper;
        this.wireMapper = JsonMapper.builder()
                .enable(JsonWriteFeature.ESCAPE_NON_ASCII)
                .build();
        try {
            Files.createDirectories(this.ticketsDir);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot create tickets dir: " + this.ticketsDir, exception);
        }
        log.info("mcp-tickets dir: {}", this.ticketsDir);
    }

    @Tool(description = """
            List support tickets from the JSON ticket store.
            Optional status filter: open, in_progress, resolved (case-insensitive).
            Returns a JSON string.""")
    public String listTickets(
            @ToolParam(description = "Optional status filter", required = false) String status,
            @ToolParam(description = "Max tickets to return (default 20)", required = false) Integer limit) {
        int max = limit != null && limit > 0 ? Math.min(limit, 100) : 20;
        String statusFilter = status != null ? status.trim().toLowerCase(Locale.ROOT) : "";
        List<Map<String, Object>> items = new ArrayList<>();
        try (Stream<Path> stream = Files.list(ticketsDir)) {
            List<Path> files = stream
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path file : files) {
                if (items.size() >= max) {
                    break;
                }
                JsonNode node = readJson(file);
                if (node == null) {
                    continue;
                }
                String ticketStatus = text(node, "status");
                if (!statusFilter.isEmpty()
                        && (ticketStatus == null || !ticketStatus.toLowerCase(Locale.ROOT).equals(statusFilter))) {
                    continue;
                }
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("id", text(node, "id"));
                summary.put("subject", text(node, "subject"));
                summary.put("status", ticketStatus);
                summary.put("priority", text(node, "priority"));
                summary.put("user", text(node, "user"));
                summary.put("file", file.getFileName().toString());
                items.add(summary);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("listTickets failed: " + exception.getMessage(), exception);
        }
        log.info("listTickets status='{}' -> {} ticket(s)", statusFilter, items.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", items.size());
        result.put("tickets", items);
        result.put("ticketsDir", ticketsDir.toString());
        return toWireJson(result);
    }

    @Tool(description = """
            Get one support ticket by id (e.g. TKT-001) or by filename (e.g. TKT-001.json).
            Returns full JSON including symptoms, logs, and product area as a JSON string.""")
    public String getTicket(
            @ToolParam(description = "Ticket id or filename") String ticketId) {
        if (ticketId == null || ticketId.isBlank()) {
            throw new IllegalArgumentException("ticketId is required");
        }
        Path file = resolveTicketFile(ticketId.trim());
        if (file == null || !Files.isRegularFile(file)) {
            Map<String, Object> missing = new LinkedHashMap<>();
            missing.put("found", false);
            missing.put("ticketId", ticketId);
            missing.put("message", "Ticket not found");
            return toWireJson(missing);
        }
        JsonNode node = readJson(file);
        if (node == null) {
            throw new IllegalStateException("Failed to parse ticket file: " + file.getFileName());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = objectMapper.convertValue(node, Map.class);
        result.put("found", true);
        result.put("file", file.getFileName().toString());
        log.info("getTicket {} -> ok", ticketId);
        return toWireJson(result);
    }

    @Tool(description = """
            Create a new support ticket JSON file.
            Required: subject. Optional: user, priority, productArea, symptoms, status (default open).
            Returns a JSON string.""")
    public String createTicket(
            @ToolParam(description = "Short subject line") String subject,
            @ToolParam(description = "User name or email", required = false) String user,
            @ToolParam(description = "Priority: low|normal|high", required = false) String priority,
            @ToolParam(description = "Product area, e.g. auth, rag, mcp", required = false) String productArea,
            @ToolParam(description = "User-described symptoms", required = false) String symptoms,
            @ToolParam(description = "Status, default open", required = false) String status) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject is required");
        }
        String id = "TKT-" + System.currentTimeMillis() % 1000000;
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", id);
        node.put("subject", subject.trim());
        node.put("status", status != null && !status.isBlank() ? status.trim() : "open");
        node.put("priority", priority != null && !priority.isBlank() ? priority.trim() : "normal");
        node.put("user", user != null ? user.trim() : "anonymous");
        node.put("productArea", productArea != null ? productArea.trim() : "general");
        node.put("symptoms", symptoms != null ? symptoms.trim() : "");
        node.put("createdAt", Instant.now().toString());
        node.putArray("logs");
        Path file = ticketsDir.resolve(id + ".json");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), node);
        } catch (Exception exception) {
            throw new IllegalStateException("createTicket failed: " + exception.getMessage(), exception);
        }
        log.info("createTicket {} -> {}", id, file.getFileName());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", true);
        result.put("id", id);
        result.put("file", file.getFileName().toString());
        return toWireJson(result);
    }

    @Tool(description = """
            Update a ticket status. Allowed values: open, in_progress, resolved.
            Transitions: open→in_progress|resolved; in_progress→open|resolved; resolved→open|in_progress.
            Returns a JSON string.""")
    public String updateTicketStatus(
            @ToolParam(description = "Ticket id or filename") String ticketId,
            @ToolParam(description = "New status: open|in_progress|resolved") String status) {
        if (ticketId == null || ticketId.isBlank()) {
            throw new IllegalArgumentException("ticketId is required");
        }
        String next = normalizeStatus(status);
        if (next == null) {
            throw new IllegalArgumentException("status must be open, in_progress, or resolved");
        }
        Path file = resolveTicketFile(ticketId.trim());
        if (file == null || !Files.isRegularFile(file)) {
            Map<String, Object> missing = new LinkedHashMap<>();
            missing.put("updated", false);
            missing.put("ticketId", ticketId);
            missing.put("message", "Ticket not found");
            return toWireJson(missing);
        }
        JsonNode parsed = readJson(file);
        if (parsed == null || !(parsed instanceof ObjectNode node)) {
            throw new IllegalStateException("Failed to parse ticket file: " + file.getFileName());
        }
        String current = normalizeStatus(text(node, "status"));
        if (current == null) {
            current = "open";
        }
        if (!current.equals(next) && !ALLOWED_TRANSITIONS.getOrDefault(current, List.of()).contains(next)) {
            Map<String, Object> denied = new LinkedHashMap<>();
            denied.put("updated", false);
            denied.put("ticketId", text(node, "id"));
            denied.put("from", current);
            denied.put("to", next);
            denied.put("allowed", ALLOWED_TRANSITIONS.get(current));
            denied.put("message", "Transition not allowed");
            return toWireJson(denied);
        }
        node.put("status", next);
        node.put("updatedAt", Instant.now().toString());
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), node);
        } catch (Exception exception) {
            throw new IllegalStateException("updateTicketStatus failed: " + exception.getMessage(), exception);
        }
        log.info("updateTicketStatus {} {} -> {}", text(node, "id"), current, next);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updated", true);
        result.put("id", text(node, "id"));
        result.put("from", current);
        result.put("status", next);
        result.put("file", file.getFileName().toString());
        result.put("allowedNext", ALLOWED_TRANSITIONS.get(next));
        return toWireJson(result);
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String value = status.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if ("inprogress".equals(value)) {
            value = "in_progress";
        }
        return ALLOWED_TRANSITIONS.containsKey(value) ? value : null;
    }

    private static final Map<String, List<String>> ALLOWED_TRANSITIONS = Map.of(
            "open", List.of("in_progress", "resolved"),
            "in_progress", List.of("open", "resolved"),
            "resolved", List.of("open", "in_progress"));

    private String toWireJson(Object value) {
        try {
            return wireMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("JSON serialize failed: " + exception.getMessage(), exception);
        }
    }

    private Path resolveTicketFile(String ticketId) {
        String raw = ticketId.trim();
        if (raw.toLowerCase(Locale.ROOT).endsWith(".json")) {
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
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .filter(path -> {
                        JsonNode node = readJson(path);
                        return node != null && raw.equalsIgnoreCase(text(node, "id"));
                    })
                    .findFirst()
                    .orElse(null);
        } catch (Exception exception) {
            return null;
        }
    }

    private JsonNode readJson(Path file) {
        try {
            return objectMapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            log.warn("Bad ticket JSON {}: {}", file.getFileName(), exception.getMessage());
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text != null && !text.isBlank() ? text : null;
    }

    private static Path resolveTicketsDir(String configured) {
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured.trim()).toAbsolutePath().normalize();
        }
        return Paths.get("data/tickets").toAbsolutePath().normalize();
    }
}

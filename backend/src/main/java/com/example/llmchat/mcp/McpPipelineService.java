package com.example.llmchat.mcp;

import com.example.llmchat.agent.OpenRouterHttpClient;
import com.example.llmchat.agent.OpenRouterHttpClient.ChatMessage;
import com.example.llmchat.dto.McpToolCallLogDto;
import com.example.llmchat.dto.PipelineRunResponse;
import com.example.llmchat.dto.PipelineStepDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class McpPipelineService {

    public static final String PIPELINE_SERVER_NAME = "mcp-pipeline";

    private static final Logger log = LoggerFactory.getLogger(McpPipelineService.class);
    private static final int PREVIEW_LIMIT = 600;

    private final ObjectProvider<List<McpSyncClient>> mcpClientsProvider;
    private final ObjectMapper objectMapper;
    private final OpenRouterHttpClient openRouterHttpClient;
    private final String model;
    private final double temperature;

    public McpPipelineService(
            ObjectProvider<List<McpSyncClient>> mcpClientsProvider,
            ObjectMapper objectMapper,
            OpenRouterHttpClient openRouterHttpClient,
            @Value("${app.openrouter.model}") String model,
            @Value("${app.agent.temperature}") double temperature) {
        this.mcpClientsProvider = mcpClientsProvider;
        this.objectMapper = objectMapper;
        this.openRouterHttpClient = openRouterHttpClient;
        this.model = model;
        this.temperature = temperature;
    }

    public PipelineRunResponse run(String query, String filename) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query is required");
        }
        String normalizedQuery = McpTextEncoding.normalize(query.trim());
        String safeFilename = filename != null && !filename.isBlank() ? filename.trim() : defaultFilename(normalizedQuery);

        McpSyncClient client = findPipelineClient();
        long totalStart = System.currentTimeMillis();
        List<PipelineStepDto> steps = new ArrayList<>();
        List<McpToolCallLogDto> toolCalls = new ArrayList<>();

        Map<String, Object> searchArgs = Map.of("query", normalizedQuery);
        StepOutcome search = invoke(client, 1, "search", searchArgs);
        steps.add(search.step());
        toolCalls.add(search.toolCall());

        JsonNode searchJson = parseJson(search.rawText());
        JsonNode itemsNode = searchJson.path("items");
        int itemCount = searchJson.path("itemCount").asInt(itemsNode.isArray() ? itemsNode.size() : 0);
        String itemsJson = itemsNode.isMissingNode() ? "[]" : itemsNode.toString();

        Map<String, Object> summarizeArgs = Map.of("itemsJson", itemsJson);
        StepOutcome summarize = invoke(client, 2, "summarize", summarizeArgs);
        steps.add(summarize.step());
        toolCalls.add(summarize.toolCall());

        JsonNode summarizeJson = parseJson(summarize.rawText());
        String summary = McpTextEncoding.normalize(summarizeJson.path("summary").asText(""));
        if (summary.isBlank()) {
            summary = "Конспект не сформирован.";
        }

        Map<String, Object> saveArgs = Map.of("summary", summary, "filename", safeFilename);
        StepOutcome save = invoke(client, 3, "saveToFile", saveArgs);
        steps.add(save.step());
        toolCalls.add(save.toolCall());

        JsonNode saveJson = parseJson(save.rawText());
        String filePath = McpTextEncoding.normalize(saveJson.path("path").asText(""));

        long totalDuration = System.currentTimeMillis() - totalStart;
        String assistantMessage = narrateWithLlm(normalizedQuery, summary, filePath, itemCount);

        log.info(
                "Pipeline completed query='{}' file='{}' durationMs={}",
                normalizedQuery,
                filePath,
                totalDuration);

        return new PipelineRunResponse(
                normalizedQuery,
                safeFilename,
                filePath,
                totalDuration,
                List.copyOf(steps),
                List.copyOf(toolCalls),
                assistantMessage);
    }

    private String narrateWithLlm(String query, String summary, String filePath, int itemCount) {
        try {
            var result = openRouterHttpClient.complete(
                    model,
                    temperature,
                    450,
                    List.of(
                            new ChatMessage(
                                    "system",
                                    """
                                    Ты преподаватель религиоведения. Студент попросил помощь с конспектом.
                                    Backend уже выполнил MCP-пайплайн (search → summarize → saveToFile).
                                    Объясни результат простым языком в 2–3 коротких абзаца: что нашли, главное содержание, куда сохранён файл.
                                    Не упоминай JSON, MCP и технические детали. Не имитируй диалог — только готовый ответ."""),
                            new ChatMessage(
                                    "user",
                                    "Тема: " + query
                                            + "\nИсточников в подборке: "
                                            + itemCount
                                            + "\nЧерновик конспекта:\n"
                                            + summary
                                            + (filePath.isBlank() ? "" : "\nФайл: " + filePath))),
                    false);
            return McpTextEncoding.normalize(result.content());
        } catch (Exception exception) {
            log.warn("Pipeline LLM narration failed: {}", exception.getMessage());
            String fileNote = filePath.isBlank() ? "" : "\n\nКонспект сохранён: " + filePath;
            return "Подборка по теме «" + query + "» готова." + fileNote + "\n\n" + summary;
        }
    }

    private StepOutcome invoke(McpSyncClient client, int stepNumber, String toolName, Map<String, Object> args) {
        long start = System.currentTimeMillis();
        String argsJson = writeJson(args);
        try {
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(toolName, args));
            if (result.isError() != null && result.isError()) {
                throw new IllegalStateException("Tool " + toolName + " returned error: " + extractText(result));
            }
            String rawText = extractText(result);
            long duration = System.currentTimeMillis() - start;
            PipelineStepDto step = new PipelineStepDto(
                    stepNumber,
                    toolName,
                    PIPELINE_SERVER_NAME,
                    McpTextEncoding.normalize(argsJson),
                    preview(rawText),
                    duration);
            McpToolCallLogDto toolCall = new McpToolCallLogDto(
                    PIPELINE_SERVER_NAME, toolName, argsJson, preview(rawText), duration);
            return new StepOutcome(step, toolCall, rawText);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Pipeline step " + stepNumber + " (" + toolName + ") failed: "
                            + (exception.getMessage() != null ? exception.getMessage() : exception),
                    exception);
        }
    }

    private McpSyncClient findPipelineClient() {
        List<McpSyncClient> clients = mcpClientsProvider.getIfAvailable();
        if (clients == null || clients.isEmpty()) {
            throw new IllegalStateException("MCP clients not available");
        }
        for (McpSyncClient client : clients) {
            if (PIPELINE_SERVER_NAME.equalsIgnoreCase(resolveServerName(client))) {
                return client;
            }
        }
        throw new IllegalStateException("mcp-pipeline server not connected — run mvn install and restart backend");
    }

    private String resolveServerName(McpSyncClient client) {
        try {
            McpSchema.Implementation info = client.getServerInfo();
            if (info != null && info.name() != null && !info.name().isBlank()) {
                return info.name();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "";
    }

    private String extractText(McpSchema.CallToolResult result) {
        if (result == null || result.content() == null) {
            return "{}";
        }
        StringBuilder builder = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent textContent) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(textContent.text());
            }
        }
        String raw = builder.length() > 0 ? builder.toString() : "{}";
        return McpTextEncoding.normalizeJson(raw);
    }

    private JsonNode parseJson(String rawText) {
        try {
            return objectMapper.readTree(rawText != null && !rawText.isBlank() ? rawText : "{}");
        } catch (Exception exception) {
            return objectMapper.createObjectNode();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return String.valueOf(value);
        }
    }

    private String preview(String raw) {
        if (raw == null) {
            return "";
        }
        String compact = McpTextEncoding.normalize(raw.replace('\n', ' ').trim());
        if (compact.length() <= PREVIEW_LIMIT) {
            return compact;
        }
        return compact.substring(0, PREVIEW_LIMIT - 1) + "…";
    }

    private String defaultFilename(String query) {
        String slug = query.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9а-яё]+", "-");
        slug = slug.replaceAll("^-+|-+$", "");
        if (slug.isBlank()) {
            slug = "pipeline-output";
        }
        if (slug.length() > 40) {
            slug = slug.substring(0, 40);
        }
        return slug + ".txt";
    }

    private record StepOutcome(PipelineStepDto step, McpToolCallLogDto toolCall, String rawText) {
    }
}

package com.example.llmchat.mcp;

import com.example.llmchat.agent.OpenRouterHttpClient;
import com.example.llmchat.agent.OpenRouterHttpClient.ChatMessage;
import com.example.llmchat.dto.McpToolCallLogDto;
import com.example.llmchat.dto.OrchestrationRunRequest;
import com.example.llmchat.dto.OrchestrationRunResponse;
import com.example.llmchat.dto.OrchestrationStepDto;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class McpOrchestrationService {

    public static final String STUDY_SERVER_NAME = "mcp-study";
    public static final String PIPELINE_SERVER_NAME = "mcp-pipeline";
    public static final String SCHEDULER_SERVER_NAME = "mcp-scheduler";

    public static final String SCENARIO_EXAM_PREP = "exam-prep";
    public static final String SCENARIO_QUICK_OUTLINE = "quick-outline";

    private static final Logger log = LoggerFactory.getLogger(McpOrchestrationService.class);
    private static final int PREVIEW_LIMIT = 600;
    private static final int REMINDER_DELAY_SECONDS = 60;

    private final ObjectProvider<List<McpSyncClient>> mcpClientsProvider;
    private final ObjectMapper objectMapper;
    private final OpenRouterHttpClient openRouterHttpClient;
    private final String model;
    private final double temperature;

    public McpOrchestrationService(
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

    public OrchestrationRunResponse run(OrchestrationRunRequest request) {
        String scenarioId = request.scenarioId() != null && !request.scenarioId().isBlank()
                ? request.scenarioId().trim()
                : SCENARIO_EXAM_PREP;
        return switch (scenarioId) {
            case SCENARIO_EXAM_PREP -> runExamPrep(request);
            case SCENARIO_QUICK_OUTLINE -> runQuickOutline(request);
            default -> throw new IllegalArgumentException("Unknown scenario: " + scenarioId);
        };
    }

    private OrchestrationRunResponse runExamPrep(OrchestrationRunRequest request) {
        if (request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("query is required");
        }

        String normalizedQuery = McpTextEncoding.normalize(request.query().trim());
        String safeFilename = request.filename() != null && !request.filename().isBlank()
                ? request.filename().trim()
                : defaultFilename(normalizedQuery);
        String subjectFilter = request.topic() != null && !request.topic().isBlank()
                ? McpTextEncoding.normalize(request.topic().trim())
                : null;

        McpSyncClient studyClient = findClient(STUDY_SERVER_NAME);
        McpSyncClient pipelineClient = findClient(PIPELINE_SERVER_NAME);
        McpSyncClient schedulerClient = findClient(SCHEDULER_SERVER_NAME);

        long totalStart = System.currentTimeMillis();
        List<OrchestrationStepDto> steps = new ArrayList<>();
        List<McpToolCallLogDto> toolCalls = new ArrayList<>();

        StepOutcome studySearch = invokeStudySearch(studyClient, normalizedQuery, subjectFilter);
        studySearch = repairStudyOutcome(studySearch, normalizedQuery, subjectFilter);
        steps.add(studySearch.step());
        toolCalls.add(studySearch.toolCall());

        int studyMatches = countStudyMatches(studySearch);
        if (studyMatches == 0) {
            for (String alternateQuery : alternateStudyQueries(normalizedQuery)) {
                StepOutcome retry = invokeStudySearch(studyClient, alternateQuery, null);
                retry = repairStudyOutcome(retry, alternateQuery, null);
                steps.add(retry.step());
                toolCalls.add(retry.toolCall());
                studyMatches = countStudyMatches(retry);
                if (studyMatches > 0) {
                    studySearch = retry;
                    break;
                }
            }
        }

        Map<String, Object> pipelineSearchArgs = Map.of("query", normalizedQuery);
        StepOutcome pipelineSearch = invoke(pipelineClient, PIPELINE_SERVER_NAME, "search", pipelineSearchArgs);
        pipelineSearch = repairPipelineOutcome(pipelineSearch, normalizedQuery);
        steps.add(pipelineSearch.step());
        toolCalls.add(pipelineSearch.toolCall());

        String itemsJson = "[]";
        if (pipelineSearch.ok()) {
            JsonNode searchJson = parseJson(pipelineSearch.rawText());
            JsonNode itemsNode = searchJson.path("items");
            itemsJson = itemsNode.isMissingNode() ? "[]" : itemsNode.toString();
        }

        Map<String, Object> summarizeArgs = Map.of("itemsJson", itemsJson);
        StepOutcome summarize = invoke(pipelineClient, PIPELINE_SERVER_NAME, "summarize", summarizeArgs);
        summarize = repairSummarizeOutcome(summarize, itemsJson);
        steps.add(summarize.step());
        toolCalls.add(summarize.toolCall());

        String summary = "Конспект не сформирован.";
        if (summarize.ok()) {
            JsonNode summarizeJson = parseJson(summarize.rawText());
            String extracted = summarizeJson.path("summary").asText("");
            if (!extracted.isBlank()) {
                summary = extracted;
            }
        }

        Map<String, Object> saveArgs = Map.of("summary", summary, "filename", safeFilename);
        StepOutcome save = invoke(pipelineClient, PIPELINE_SERVER_NAME, "saveToFile", saveArgs);
        steps.add(save.step());
        toolCalls.add(save.toolCall());

        String filePath = "";
        if (save.ok()) {
            JsonNode saveJson = parseJson(save.rawText());
            filePath = McpTextEncoding.normalize(saveJson.path("path").asText(""));
        }

        String reminderMessage = "Проверь конспект по теме «" + normalizedQuery + "»";
        Map<String, Object> reminderArgs = Map.of(
                "message", reminderMessage,
                "delaySeconds", REMINDER_DELAY_SECONDS);
        StepOutcome reminder = invoke(schedulerClient, SCHEDULER_SERVER_NAME, "scheduleReminder", reminderArgs);
        steps.add(reminder.step());
        toolCalls.add(reminder.toolCall());

        long totalDuration = System.currentTimeMillis() - totalStart;
        String studyContext = formatStudyContext(studySearch);
        if (studyMatches == 0) {
            studyMatches = countStudyMatches(studySearch);
        }
        int itemCount = 0;
        if (pipelineSearch.ok()) {
            JsonNode searchJson = parseJson(pipelineSearch.rawText());
            JsonNode itemsNode = searchJson.path("items");
            itemCount = searchJson.path("itemCount").asInt(itemsNode.isArray() ? itemsNode.size() : 0);
        }

        String assistantMessage = narrateExamPrep(
                normalizedQuery, studyMatches, studyContext, summary, filePath, itemCount, REMINDER_DELAY_SECONDS);

        log.info(
                "Orchestration exam-prep completed query='{}' file='{}' durationMs={}",
                normalizedQuery,
                filePath,
                totalDuration);

        return new OrchestrationRunResponse(
                List.copyOf(steps),
                assistantMessage,
                List.copyOf(toolCalls),
                totalDuration,
                SCENARIO_EXAM_PREP);
    }

    private OrchestrationRunResponse runQuickOutline(OrchestrationRunRequest request) {
        String normalizedQuery = request.query() != null && !request.query().isBlank()
                ? McpTextEncoding.normalize(request.query().trim())
                : null;
        String subject = request.topic() != null && !request.topic().isBlank()
                ? McpTextEncoding.normalize(request.topic().trim())
                : normalizedQuery;

        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("topic or query is required for quick-outline");
        }

        McpSyncClient studyClient = findClient(STUDY_SERVER_NAME);
        long totalStart = System.currentTimeMillis();
        List<OrchestrationStepDto> steps = new ArrayList<>();
        List<McpToolCallLogDto> toolCalls = new ArrayList<>();

        if (normalizedQuery != null) {
            Map<String, Object> searchTopicArgs = Map.of("query", normalizedQuery, "subject", subject);
            StepOutcome studySearch = invoke(studyClient, STUDY_SERVER_NAME, "searchTopic", searchTopicArgs);
            steps.add(studySearch.step());
            toolCalls.add(studySearch.toolCall());
        }

        Map<String, Object> outlineArgs = Map.of("subject", subject);
        StepOutcome outline = invoke(studyClient, STUDY_SERVER_NAME, "getExamOutline", outlineArgs);
        steps.add(outline.step());
        toolCalls.add(outline.toolCall());

        long totalDuration = System.currentTimeMillis() - totalStart;
        int topicCount = 0;
        if (outline.ok()) {
            JsonNode outlineJson = parseJson(outline.rawText());
            topicCount = outlineJson.path("topicCount").asInt(outlineJson.path("topics").size());
        }

        String assistantMessage = narrateQuickOutline(subject, topicCount, outline.rawText());

        log.info("Orchestration quick-outline completed subject='{}' durationMs={}", subject, totalDuration);

        return new OrchestrationRunResponse(
                List.copyOf(steps),
                assistantMessage,
                List.copyOf(toolCalls),
                totalDuration,
                SCENARIO_QUICK_OUTLINE);
    }

    private String narrateExamPrep(
            String query,
            int studyMatches,
            String studyContext,
            String summary,
            String filePath,
            int itemCount,
            int delaySeconds) {
        try {
            var result = openRouterHttpClient.complete(
                    model,
                    temperature,
                    500,
                    List.of(
                            new ChatMessage(
                                    "system",
                                    """
                                    Ты преподаватель религиоведения. Студент готовится к экзамену.
                                    Backend выполнил оркестрацию MCP: справочник → pipeline → напоминание.
                                    Объясни результат простым языком в 2–3 коротких абзаца: что нашли в справочнике,
                                    главное в конспекте, куда сохранён файл, когда придёт напоминание.
                                    Если совпадений в справочнике > 0 — обязательно упомяни найденную тему.
                                    Не упоминай JSON, MCP и технические детали."""),
                            new ChatMessage(
                                    "user",
                                    "Тема: "
                                            + query
                                            + "\nСовпадений в справочнике: "
                                            + studyMatches
                                            + (studyContext.isBlank()
                                                    ? ""
                                                    : "\nНайдено в справочнике:\n" + studyContext)
                                            + "\nИсточников в pipeline: "
                                            + itemCount
                                            + "\nКонспект:\n"
                                            + summary
                                            + (filePath.isBlank() ? "" : "\nФайл: " + filePath)
                                            + "\nНапоминание через "
                                            + delaySeconds
                                            + " сек.")),
                    false);
            return McpTextEncoding.normalize(result.content());
        } catch (Exception exception) {
            log.warn("Orchestration LLM narration failed: {}", exception.getMessage());
            String fileNote = filePath.isBlank() ? "" : "\n\nКонспект сохранён: " + filePath;
            return "Подготовка по теме «"
                    + query
                    + "» завершена."
                    + fileNote
                    + "\n\n"
                    + summary
                    + "\n\nНапоминание через "
                    + delaySeconds
                    + " сек.";
        }
    }

    private String narrateQuickOutline(String subject, int topicCount, String outlineRaw) {
        try {
            var result = openRouterHttpClient.complete(
                    model,
                    temperature,
                    350,
                    List.of(
                            new ChatMessage(
                                    "system",
                                    """
                                    Ты преподаватель. Backend получил план экзамена из учебного справочника.
                                    Кратко (1–2 абзаца) перечисли темы для подготовки. Без JSON и MCP."""),
                            new ChatMessage("user", "Предмет: " + subject + "\nТем: " + topicCount + "\nДанные:\n" + outlineRaw)),
                    false);
            return McpTextEncoding.normalize(result.content());
        } catch (Exception exception) {
            log.warn("Quick-outline LLM narration failed: {}", exception.getMessage());
            return "План экзамена по «" + subject + "»: " + topicCount + " тем(ы). Смотрите шаги оркестрации.";
        }
    }

    private StepOutcome repairStudyOutcome(StepOutcome outcome, String query, String subject) {
        if (!outcome.ok() || outcome.rawText() == null) {
            return outcome;
        }
        String repaired = McpOrchestrationResultRepair.repairStudySearch(outcome.rawText(), query, subject, objectMapper);
        return rebuildOutcome(outcome, repaired);
    }

    private StepOutcome repairPipelineOutcome(StepOutcome outcome, String query) {
        if (!outcome.ok() || outcome.rawText() == null) {
            return outcome;
        }
        String repaired = McpOrchestrationResultRepair.repairPipelineSearch(outcome.rawText(), query, objectMapper);
        return rebuildOutcome(outcome, repaired);
    }

    private StepOutcome repairSummarizeOutcome(StepOutcome outcome, String itemsJson) {
        if (!outcome.ok() || outcome.rawText() == null) {
            return outcome;
        }
        String repaired = McpOrchestrationResultRepair.repairSummarize(outcome.rawText(), itemsJson, objectMapper);
        return rebuildOutcome(outcome, repaired);
    }

    private StepOutcome rebuildOutcome(StepOutcome outcome, String rawText) {
        OrchestrationStepDto step = new OrchestrationStepDto(
                outcome.step().serverName(),
                outcome.step().toolName(),
                outcome.step().args(),
                preview(rawText),
                outcome.step().durationMs(),
                outcome.step().status());
        McpToolCallLogDto toolCall = new McpToolCallLogDto(
                outcome.toolCall().serverName(),
                outcome.toolCall().toolName(),
                outcome.toolCall().arguments(),
                preview(rawText),
                outcome.toolCall().durationMs());
        return new StepOutcome(step, toolCall, rawText, true);
    }

    private StepOutcome invokeStudySearch(McpSyncClient studyClient, String query, String subjectFilter) {
        Map<String, Object> args = new HashMap<>();
        args.put("query", query);
        if (subjectFilter != null && !subjectFilter.isBlank()) {
            args.put("subject", subjectFilter);
        }
        return invoke(studyClient, STUDY_SERVER_NAME, "searchTopic", args);
    }

    private int countStudyMatches(StepOutcome studySearch) {
        if (!studySearch.ok()) {
            return 0;
        }
        JsonNode studyJson = parseJson(studySearch.rawText());
        return studyJson.path("matchCount").asInt(studyJson.path("matches").size());
    }

    private String formatStudyContext(StepOutcome studySearch) {
        if (!studySearch.ok()) {
            return "";
        }
        JsonNode matches = parseJson(studySearch.rawText()).path("matches");
        if (!matches.isArray() || matches.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode match : matches) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append("- ")
                    .append(McpTextEncoding.normalize(match.path("topic").asText("")))
                    .append(": ")
                    .append(McpTextEncoding.normalize(match.path("summary").asText("")));
        }
        return builder.toString();
    }

    private List<String> alternateStudyQueries(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String lower = query.trim().toLowerCase(Locale.ROOT);
        if (lower.contains("иман")) {
            return List.of("шесть столпов веры", "столпов веры");
        }
        return List.of();
    }

    private StepOutcome invoke(
            McpSyncClient client, String serverName, String toolName, Map<String, Object> args) {
        long start = System.currentTimeMillis();
        String argsJson = writeJson(args);
        try {
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(toolName, args));
            if (result.isError() != null && result.isError()) {
                String errorText = extractText(result);
                long duration = System.currentTimeMillis() - start;
                OrchestrationStepDto step = new OrchestrationStepDto(
                        serverName,
                        toolName,
                        McpTextEncoding.normalize(argsJson),
                        preview(errorText),
                        duration,
                        "error");
                McpToolCallLogDto toolCall = new McpToolCallLogDto(
                        serverName, toolName, argsJson, preview(errorText), duration);
                log.warn("Orchestration step {}.{} error: {}", serverName, toolName, errorText);
                return new StepOutcome(step, toolCall, null, false);
            }
            String rawText = extractText(result);
            long duration = System.currentTimeMillis() - start;
            OrchestrationStepDto step = new OrchestrationStepDto(
                    serverName,
                    toolName,
                    McpTextEncoding.normalize(argsJson),
                    preview(rawText),
                    duration,
                    "ok");
            McpToolCallLogDto toolCall = new McpToolCallLogDto(
                    serverName, toolName, argsJson, preview(rawText), duration);
            return new StepOutcome(step, toolCall, rawText, true);
        } catch (Exception exception) {
            long duration = System.currentTimeMillis() - start;
            String errorMessage = exception.getMessage() != null ? exception.getMessage() : String.valueOf(exception);
            OrchestrationStepDto step = new OrchestrationStepDto(
                    serverName,
                    toolName,
                    McpTextEncoding.normalize(argsJson),
                    preview(errorMessage),
                    duration,
                    "error");
            McpToolCallLogDto toolCall = new McpToolCallLogDto(
                    serverName, toolName, argsJson, preview(errorMessage), duration);
            log.warn("Orchestration step {}.{} failed: {}", serverName, toolName, errorMessage);
            return new StepOutcome(step, toolCall, null, false);
        }
    }

    private McpSyncClient findClient(String serverName) {
        List<McpSyncClient> clients = mcpClientsProvider.getIfAvailable();
        if (clients == null || clients.isEmpty()) {
            throw new IllegalStateException("MCP clients not available");
        }
        for (McpSyncClient client : clients) {
            if (serverName.equalsIgnoreCase(resolveServerName(client))) {
                return client;
            }
        }
        throw new IllegalStateException(serverName + " server not connected — run mvn install and restart backend");
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
        String normalized = raw.trim().startsWith("{") || raw.trim().startsWith("[")
                ? McpTextEncoding.normalizeJson(raw)
                : McpTextEncoding.normalize(raw);
        String compact = normalized.replace('\n', ' ').trim();
        if (compact.length() <= PREVIEW_LIMIT) {
            return compact;
        }
        return compact.substring(0, PREVIEW_LIMIT - 1) + "…";
    }

    private String defaultFilename(String query) {
        String slug = query.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9а-яё]+", "-");
        slug = slug.replaceAll("^-+|-+$", "");
        if (slug.isBlank()) {
            slug = "orchestration-output";
        }
        if (slug.length() > 40) {
            slug = slug.substring(0, 40);
        }
        return slug + ".txt";
    }

    private record StepOutcome(
            OrchestrationStepDto step, McpToolCallLogDto toolCall, String rawText, boolean ok) {
    }
}

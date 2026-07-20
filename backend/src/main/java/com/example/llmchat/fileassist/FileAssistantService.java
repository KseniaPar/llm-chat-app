package com.example.llmchat.fileassist;

import com.example.llmchat.config.AgentChatClientConfig;
import com.example.llmchat.dto.FileWriteResultDto;
import com.example.llmchat.dto.McpToolCallLogDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Day 34 — goal-driven file assistant over mcp-files.
 */
@Service
public class FileAssistantService {

    private static final Logger log = LoggerFactory.getLogger(FileAssistantService.class);
    private static final String PLACEHOLDER_KEY = "local-llm-not-used";

    private static final String SYSTEM_PROMPT = """
            Ты ассистент для работы с файлами репозитория llm-chat-app.
            Пользователь задаёт цель на уровне задачи — не проси его называть точные пути, если можно найти самому.

            Алгоритм:
            1. Сначала searchFiles и/или listFiles, чтобы найти релевантные файлы.
            2. readFile для анализа содержимого (2–3+ файла для отчётов).
            3. writeFile для сохранения отчёта или обновления документации.

            Инструменты mcp-files:
            • listFiles(prefix, limit)
            • searchFiles(query, glob, limit)
            • readFile(path)
            • writeFile(path, content, dryRun) — только allowlist: project/docs/**, docs/**, adr/**, README.md, CHANGELOG.md

            Пиши на русском. После записи файлов кратко перечисли, что сделано и какие пути изменены.
            Если dryRun=true в запросе — вызывай writeFile с dryRun=true и покажи diff, не сохраняй.
            """;

    private final ChatClient fileAssistChatClient;
    private final ToolCallback[] fileAssistToolCallbacks;
    private final FileAssistTools fileAssistTools;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String openRouterModel;
    private final double temperature;
    private final int maxTokens;

    public FileAssistantService(
            @Qualifier("fileAssistChatClient") ChatClient fileAssistChatClient,
            @Qualifier("fileAssistToolCallbacks") ToolCallback[] fileAssistToolCallbacks,
            FileAssistTools fileAssistTools,
            ObjectMapper objectMapper,
            @Value("${app.openrouter.api-key:}") String apiKey,
            @Value("${app.openrouter.model}") String openRouterModel,
            @Value("${app.agent.temperature:0.5}") double temperature,
            @Value("${app.fileassist.max-tokens:2000}") int maxTokens) {
        this.fileAssistChatClient = fileAssistChatClient;
        this.fileAssistToolCallbacks = fileAssistToolCallbacks;
        this.fileAssistTools = fileAssistTools;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.openRouterModel = openRouterModel;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    public boolean cloudConfigured() {
        return !apiKey.isBlank() && !PLACEHOLDER_KEY.equals(apiKey);
    }

    public FileAnswer executeGoal(String goal, boolean dryRun) {
        long started = System.currentTimeMillis();
        String g = goal != null ? goal.trim() : "";
        if (g.isBlank()) {
            throw new IllegalArgumentException("goal is required");
        }
        if (!cloudConfigured()) {
            throw new IllegalStateException("OPENROUTER_API_KEY не задан — файловый ассистент недоступен.");
        }

        AgentChatClientConfig.beginToolCallRecording();
        FileWriteCollector.begin();
        List<McpToolCallLogDto> prefetched = new ArrayList<>();
        String listedJson = fileAssistTools.listFiles("project/docs", 30);
        prefetched.add(new McpToolCallLogDto(
                FileAssistTools.TOOL_SERVER_NAME,
                "listFiles",
                "{\"prefix\":\"project/docs\",\"limit\":30}",
                listedJson != null && listedJson.length() > 200 ? listedJson.substring(0, 200) + "…" : listedJson,
                0L));

        String userMessage = """
                Цель:
                %s

                dryRun: %s

                --- project/docs (уже загружено listFiles) ---
                %s
                """.formatted(
                g,
                dryRun,
                listedJson == null || listedJson.isBlank() ? "(список недоступен)" : listedJson);

        String answerText;
        String model = openRouterModel;
        try {
            ChatOptions options = OpenAiChatOptions.builder()
                    .model(openRouterModel)
                    .temperature(temperature)
                    .maxTokens(maxTokens)
                    .build();

            ChatResponse chatResponse = fileAssistChatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userMessage)
                    .toolCallbacks(fileAssistToolCallbacks)
                    .options(options)
                    .call()
                    .chatResponse();

            answerText = chatResponse != null && chatResponse.getResult() != null
                    ? chatResponse.getResult().getOutput().getText()
                    : "";
            if (answerText == null || answerText.isBlank()) {
                answerText = "Модель не вернула текст.";
            } else {
                answerText = answerText.trim();
            }
            if (chatResponse != null
                    && chatResponse.getMetadata() != null
                    && chatResponse.getMetadata().getModel() != null) {
                model = chatResponse.getMetadata().getModel();
            }
        } catch (Exception exception) {
            log.warn("FileAssist failed: {}", exception.getMessage());
            answerText = "Не удалось выполнить задачу: " + exception.getMessage();
        }

        List<McpToolCallLogDto> extraTools = AgentChatClientConfig.drainToolCallRecording();
        List<McpToolCallLogDto> allTools = new ArrayList<>(prefetched);
        allTools.addAll(extraTools);

        List<FileWriteResultDto> writes = FileWriteCollector.drain(objectMapper);
        List<String> appliedPaths = writes.stream()
                .filter(write -> write.written() && !write.dryRun())
                .map(FileWriteResultDto::path)
                .distinct()
                .toList();

        long durationMs = System.currentTimeMillis() - started;
        log.info("FileAssist goal done in {} ms, writes={}, tools={}", durationMs, writes.size(), allTools.size());
        return new FileAnswer(g, answerText, model, durationMs, dryRun, appliedPaths, writes, allTools);
    }

    public record FileAnswer(
            String goal,
            String answer,
            String model,
            long durationMs,
            boolean dryRun,
            List<String> appliedPaths,
            List<FileWriteResultDto> writes,
            List<McpToolCallLogDto> mcpToolCalls) {
    }
}

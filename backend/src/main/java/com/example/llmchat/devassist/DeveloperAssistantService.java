package com.example.llmchat.devassist;

import com.example.llmchat.config.AgentChatClientConfig;
import com.example.llmchat.dto.McpToolCallLogDto;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Day 31 developer assistant: tool-calling agent over project RAG + mcp-git.
 * Prefetches branch + docs so typical questions need one LLM round-trip.
 */
@Service
public class DeveloperAssistantService {

    private static final Logger log = LoggerFactory.getLogger(DeveloperAssistantService.class);

    private static final Pattern HELP_PREFIX = Pattern.compile("^/help\\b[\\s,:;.-]*", Pattern.CASE_INSENSITIVE);

    private static final String SYSTEM_PROMPT = """
            Ты ассистент разработчика репозитория llm-chat-app.
            Отвечай на русском, кратко и по делу (обычно 3–8 предложений).

            В сообщении пользователя уже есть текущая git-ветка и фрагменты документации.
            Сначала ответь по этому контексту.

            Инструменты вызывай только если не хватает данных:
            • retrieveProjectDocs — дополнительный поиск по docs
            • listRepoFiles — список файлов (pathPrefix)
            • getWorkingTreeDiff — unstaged diff
            • getCurrentBranch — только если явно просят ветку и её нет в контексте

            Не выдумывай структуру репозитория. Не упоминай учебный чат о православии.""";

    private static final String EMPTY_HELP = """
            Ассистент разработчика llm-chat-app.

            Задайте вопрос о структуре репозитория, модулях, API, RAG или git.

            Примеры:
            • Какие модули в monorepo?
            • Где лежит RAG?
            • Какие REST API у агента?
            • Какая сейчас git-ветка?
            • Что изменено в working tree?

            Источники: README.md, project/docs + git.
            UI: /dev.html.""";

    private final ChatClient devAssistChatClient;
    private final ToolCallback[] devAssistToolCallbacks;
    private final DevAssistProjectTools projectTools;
    private final GitMcpFacade gitMcpFacade;
    private final DevAssistLlmConfig llmConfig;
    private final ObjectMapper objectMapper;
    private final String openRouterModel;
    private final double temperature;
    private final int maxTokens;

    public DeveloperAssistantService(
            @Qualifier("devAssistChatClient") ChatClient devAssistChatClient,
            @Qualifier("devAssistToolCallbacks") ToolCallback[] devAssistToolCallbacks,
            DevAssistProjectTools projectTools,
            GitMcpFacade gitMcpFacade,
            DevAssistLlmConfig llmConfig,
            ObjectMapper objectMapper,
            @Value("${app.openrouter.model}") String openRouterModel,
            @Value("${app.agent.temperature:0.5}") double temperature,
            @Value("${app.devassist.max-tokens:1024}") int maxTokens) {
        this.devAssistChatClient = devAssistChatClient;
        this.devAssistToolCallbacks = devAssistToolCallbacks;
        this.projectTools = projectTools;
        this.gitMcpFacade = gitMcpFacade;
        this.llmConfig = llmConfig;
        this.objectMapper = objectMapper;
        this.openRouterModel = openRouterModel;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    public static boolean isHelpCommand(String prompt) {
        if (prompt == null) {
            return false;
        }
        String trimmed = prompt.trim();
        return trimmed.toLowerCase(Locale.ROOT).startsWith("/help");
    }

    public HelpAnswer answerQuestion(String question) {
        if (question == null || question.isBlank()) {
            return answer("/help");
        }
        String trimmed = question.trim();
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("/help")) {
            return answer(trimmed);
        }
        return answer("/help " + trimmed);
    }

    public HelpAnswer answer(String rawPrompt) {
        long started = System.currentTimeMillis();
        String question = extractQuestion(rawPrompt);
        List<String> logs = new ArrayList<>();
        logs.add("DEV-ASSIST → /help (prefetch + tools)");

        if (question.isBlank()) {
            return emptyHelp(started, logs);
        }

        if (!llmConfig.cloudConfigured()) {
            logs.add("DEV-ASSIST → OPENROUTER_API_KEY missing");
            return new HelpAnswer(
                    llmConfig.notReadyMessage()
                            + " Ассистент разработчика требует облачную LLM (OpenRouter).",
                    openRouterModel,
                    System.currentTimeMillis() - started,
                    List.of(),
                    null,
                    List.of(),
                    List.copyOf(logs));
        }

        AgentChatClientConfig.beginToolCallRecording();
        DevAssistProjectTools.beginSourceRecording();

        long prefetchStarted = System.currentTimeMillis();
        // Branch on a side thread; RAG on this thread so ThreadLocal sources stay correct.
        CompletableFuture<GitMcpFacade.ToolResult> branchFuture =
                CompletableFuture.supplyAsync(gitMcpFacade::getCurrentBranch);
        String docsContext = projectTools.retrieveProjectDocs(question);
        GitMcpFacade.ToolResult branch = branchFuture.join();
        logs.add("DEV-ASSIST → prefetch " + (System.currentTimeMillis() - prefetchStarted) + " ms");

        List<McpToolCallLogDto> prefetchedTools = new ArrayList<>();
        prefetchedTools.add(branch.toolCall());
        // retrieveProjectDocs records sources via ThreadLocal; also log as synthetic tool for UI
        prefetchedTools.add(new McpToolCallLogDto(
                DevAssistProjectTools.TOOL_SERVER_NAME,
                "retrieveProjectDocs",
                "{\"query\":\"" + question.replace("\"", "'") + "\"}",
                docsContext != null && docsContext.length() > 200
                        ? docsContext.substring(0, 200) + "…"
                        : docsContext,
                System.currentTimeMillis() - prefetchStarted));

        String userMessage = """
                Вопрос: %s

                --- Git (уже загружено) ---
                %s

                --- Документация (уже загружено) ---
                %s
                """.formatted(
                question,
                branch.ok() ? branch.text() : "(ветка недоступна: " + branch.text() + ")",
                docsContext == null || docsContext.isBlank() ? "(документация не найдена)" : docsContext);

        String answerText;
        String model = openRouterModel;
        try {
            ChatOptions options = OpenAiChatOptions.builder()
                    .model(openRouterModel)
                    .temperature(temperature)
                    .maxTokens(maxTokens)
                    .build();

            ChatResponse chatResponse = devAssistChatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userMessage)
                    .toolCallbacks(devAssistToolCallbacks)
                    .options(options)
                    .call()
                    .chatResponse();

            answerText = chatResponse != null && chatResponse.getResult() != null
                    ? chatResponse.getResult().getOutput().getText()
                    : "";
            if (answerText == null || answerText.isBlank()) {
                answerText = "Модель не вернула текст. Проверьте индекс документации.";
                logs.add("DEV-ASSIST → empty LLM content");
            } else {
                answerText = answerText.trim();
                logs.add("DEV-ASSIST → ответ LLM");
            }
            if (chatResponse != null
                    && chatResponse.getMetadata() != null
                    && chatResponse.getMetadata().getModel() != null) {
                model = chatResponse.getMetadata().getModel();
            }
        } catch (Exception exception) {
            log.warn("DevAssist failed: {}", exception.getMessage());
            logs.add("DEV-ASSIST → error: " + exception.getMessage());
            answerText = "Не удалось получить ответ: " + exception.getMessage();
        }

        List<McpToolCallLogDto> extraTools = AgentChatClientConfig.drainToolCallRecording();
        List<String> sources = DevAssistProjectTools.drainSources();

        List<McpToolCallLogDto> allTools = new ArrayList<>(prefetchedTools);
        allTools.addAll(extraTools);
        for (McpToolCallLogDto call : allTools) {
            logs.add("TOOL → " + call.serverName() + "/" + call.toolName()
                    + " (" + call.durationMs() + " ms)");
        }

        String gitBranchJson = branch.ok() ? branch.text() : extractBranchJson(allTools);

        long durationMs = System.currentTimeMillis() - started;
        log.info("/help done in {} ms, sources={}, tools={}", durationMs, sources.size(), allTools.size());
        return new HelpAnswer(
                answerText,
                model,
                durationMs,
                List.copyOf(sources),
                gitBranchJson,
                List.copyOf(allTools),
                List.copyOf(logs));
    }

    private HelpAnswer emptyHelp(long started, List<String> logs) {
        GitMcpFacade.ToolResult branch = gitMcpFacade.getCurrentBranch();
        List<McpToolCallLogDto> toolCalls = List.of(branch.toolCall());
        String branchLine = branch.ok()
                ? "Текущая ветка: " + branch.text()
                : "Git-ветка недоступна: " + branch.text();
        logs.add("DEV-ASSIST → краткая справка + getCurrentBranch");
        return new HelpAnswer(
                EMPTY_HELP + "\n\n" + branchLine,
                openRouterModel,
                System.currentTimeMillis() - started,
                List.of(),
                branch.ok() ? branch.text() : null,
                toolCalls,
                List.copyOf(logs));
    }

    private String extractBranchJson(List<McpToolCallLogDto> toolCalls) {
        for (McpToolCallLogDto call : toolCalls) {
            if (!"getCurrentBranch".equals(call.toolName())) {
                continue;
            }
            String preview = call.resultPreview();
            if (preview == null || preview.isBlank()) {
                continue;
            }
            try {
                JsonNode node = objectMapper.readTree(preview.replace("…", "").trim());
                if (node.has("branch")) {
                    return objectMapper.writeValueAsString(node);
                }
            } catch (Exception ignored) {
                // preview may be truncated
            }
            if (preview.contains("\"branch\"")) {
                return preview;
            }
        }
        return null;
    }

    private static String extractQuestion(String rawPrompt) {
        if (rawPrompt == null) {
            return "";
        }
        String trimmed = rawPrompt.trim();
        Matcher matcher = HELP_PREFIX.matcher(trimmed);
        if (matcher.find()) {
            return trimmed.substring(matcher.end()).trim();
        }
        return trimmed;
    }

    public record HelpAnswer(
            String answer,
            String model,
            long durationMs,
            List<String> sources,
            String gitBranchJson,
            List<McpToolCallLogDto> mcpToolCalls,
            List<String> logs) {
    }
}

package com.example.llmchat.review;

import com.example.llmchat.dto.PrReviewRequest;
import com.example.llmchat.dto.PrReviewResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Day 32 — AI code review for PRs / local diffs using project docs + changed files + unified diff.
 */
@Service
public class PrReviewService {

    private static final Logger log = LoggerFactory.getLogger(PrReviewService.class);

    private static final String PLACEHOLDER_KEY = "local-llm-not-used";

    private static final String SYSTEM_PROMPT = """
            Ты — senior reviewer репозитория llm-chat-app (Spring Boot + Vite + MCP + RAG).
            Пиши ревью на русском, конкретно и по делу. Ссылайся на файлы/символы из diff.
            Не выдумывай код, которого нет в контексте.
            Обязательно используй ровно три секции с такими заголовками Markdown:

            ## Потенциальные баги
            ## Архитектурные проблемы
            ## Рекомендации

            В каждой секции — маркированный список (или «не обнаружено» с кратким пояснением).
            В конце можно добавить одну строку «Вердикт: …».
            """;

    private static final Pattern SECTION = Pattern.compile(
            "(?ms)^##\\s+(Потенциальные баги|Архитектурные проблемы|Рекомендации)\\s*$(.*?)(?=^##\\s+|\\z)");

    private final PrReviewContextBuilder contextBuilder;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String chatUrl;
    private final String model;
    private final double temperature;
    private final int maxTokens;

    public PrReviewService(
            PrReviewContextBuilder contextBuilder,
            ObjectMapper objectMapper,
            @Value("${app.openrouter.api-key:}") String apiKey,
            @Value("${app.openrouter.base-url:https://openrouter.ai/api}") String baseUrl,
            @Value("${app.openrouter.model:openai/gpt-4o-mini}") String model,
            @Value("${app.review.temperature:0.2}") double temperature,
            @Value("${app.review.max-tokens:1800}") int maxTokens) {
        this.contextBuilder = contextBuilder;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.chatUrl = baseUrl.replaceAll("/$", "") + "/v1/chat/completions";
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(20_000);
        factory.setReadTimeout(120_000);
        this.restTemplate = new RestTemplate(factory);
    }

    public boolean isReady() {
        return !apiKey.isBlank() && !PLACEHOLDER_KEY.equals(apiKey);
    }

    public PrReviewResponse analyze(PrReviewRequest request) {
        long started = System.currentTimeMillis();
        if (!isReady()) {
            throw new IllegalStateException(
                    "OPENROUTER_API_KEY не задан — AI review недоступен.");
        }

        String title = request != null && request.title() != null ? request.title().trim() : "";
        String diff = request != null && request.diff() != null ? request.diff() : "";
        List<String> changedFiles = request != null && request.changedFiles() != null
                ? new ArrayList<>(request.changedFiles())
                : new ArrayList<>();

        if (diff.isBlank()) {
            String base = request != null ? request.baseRef() : null;
            if (base != null && !base.isBlank()) {
                diff = contextBuilder.collectDiffVs(base);
                if (changedFiles.isEmpty()) {
                    changedFiles.addAll(contextBuilder.listChangedFilesVs(base));
                }
            } else {
                diff = contextBuilder.collectWorkingTreeDiff();
            }
        }
        if (changedFiles.isEmpty()) {
            changedFiles.addAll(contextBuilder.collectChangedFiles(diff));
        }
        if (diff.isBlank()) {
            throw new IllegalArgumentException(
                    "Пустой diff. Передайте diff в теле запроса или сделайте локальные изменения / укажите baseRef.");
        }

        PrReviewContextBuilder.BuiltContext context = contextBuilder.build(title, diff, changedFiles);
        String userPrompt = buildUserPrompt(title, request, context, changedFiles);

        String markdown = callOpenRouter(userPrompt);
        markdown = ensureSections(markdown);

        Map<String, String> sections = parseSections(markdown);
        long durationMs = System.currentTimeMillis() - started;
        log.info("PR review done in {} ms, diffChars={}, files={}",
                durationMs, context.diff().length(), changedFiles.size());

        return new PrReviewResponse(
                markdown,
                model,
                durationMs,
                context.diff().length(),
                changedFiles.size(),
                context.sources(),
                sections.getOrDefault("Потенциальные баги", ""),
                sections.getOrDefault("Архитектурные проблемы", ""),
                sections.getOrDefault("Рекомендации", ""));
    }

    private String buildUserPrompt(
            String title,
            PrReviewRequest request,
            PrReviewContextBuilder.BuiltContext context,
            List<String> changedFiles) {
        StringBuilder filesList = new StringBuilder();
        for (String file : changedFiles) {
            filesList.append("- ").append(file).append('\n');
        }
        String base = request != null && request.baseRef() != null ? request.baseRef() : "";
        String head = request != null && request.headRef() != null ? request.headRef() : "";

        return """
                Заголовок PR/изменения: %s
                Base: %s
                Head: %s

                Изменённые файлы:
                %s
                --- Документация проекта ---
                %s

                --- Релевантные фрагменты RAG (если есть) ---
                %s

                --- Фрагменты изменённых файлов ---
                %s

                --- Unified diff ---
                %s
                """.formatted(
                title.isBlank() ? "(без заголовка)" : title,
                base.isBlank() ? "(не указан)" : base,
                head.isBlank() ? "(не указан)" : head,
                filesList.isEmpty() ? "(не указаны)" : filesList,
                context.docs(),
                context.ragHints().isBlank() ? "(нет)" : context.ragHints(),
                context.fileExcerpts(),
                context.diff());
    }

    private String callOpenRouter(String userPrompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", userPrompt)));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        headers.set("HTTP-Referer", "https://github.com/KseniaPar/llm-chat-app");
        headers.set("X-OpenRouter-Title", "llm-chat-app-pr-review");

        ResponseEntity<String> response = restTemplate.postForEntity(
                chatUrl, new HttpEntity<>(body, headers), String.class);
        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull() || content.asText().isBlank()) {
                throw new IllegalStateException("OpenRouter вернул пустой content");
            }
            return content.asText().trim();
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Не удалось разобрать ответ OpenRouter: " + exception.getMessage(),
                    exception);
        }
    }

    private static String ensureSections(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return """
                    ## Потенциальные баги
                    - не удалось получить ревью

                    ## Архитектурные проблемы
                    - не удалось получить ревью

                    ## Рекомендации
                    - повторите запрос позже
                    """;
        }
        String lower = markdown.toLowerCase(Locale.ROOT);
        if (lower.contains("потенциальные баги")
                && lower.contains("архитектурные проблемы")
                && lower.contains("рекомендации")) {
            return markdown;
        }
        return """
                ## Потенциальные баги
                - см. текст ниже (модель не разметила секции)

                ## Архитектурные проблемы
                - см. текст ниже

                ## Рекомендации
                - см. текст ниже

                ---
                """ + markdown;
    }

    private static Map<String, String> parseSections(String markdown) {
        Map<String, String> map = new LinkedHashMap<>();
        Matcher matcher = SECTION.matcher(markdown);
        while (matcher.find()) {
            map.put(matcher.group(1).trim(), matcher.group(2).trim());
        }
        return map;
    }
}

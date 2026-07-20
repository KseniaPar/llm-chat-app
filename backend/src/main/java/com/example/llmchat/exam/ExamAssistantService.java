package com.example.llmchat.exam;

import com.example.llmchat.config.AgentChatClientConfig;
import com.example.llmchat.dto.ExamCitationDto;
import com.example.llmchat.dto.McpToolCallLogDto;
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

@Service
public class ExamAssistantService {

    private static final Logger log = LoggerFactory.getLogger(ExamAssistantService.class);
    private static final String PLACEHOLDER_KEY = "local-llm-not-used";

    private static final String SYSTEM_PROMPT = """
            Ты экзаменационный ассистент по религиоведению.
            Отвечай на русском, опираясь ТОЛЬКО на фрагменты всех загруженных лекций.
            В сообщении уже могут быть релевантные цитаты с таймкодами из разных лекций.
            При необходимости вызывай retrieveExamLecture для уточнения по всему корпусу.
            Обязательно указывай источник в формате «Название лекции @ mm:ss».
            Если в материале нет ответа — честно скажи.
            Не выдумывай факты вне транскрипта.
            """;

    private final ChatClient examChatClient;
    private final ToolCallback[] examToolCallbacks;
    private final ExamLectureTools lectureTools;
    private final ExamPipelineService pipelineService;
    private final String apiKey;
    private final String openRouterModel;
    private final double temperature;
    private final int maxTokens;

    public ExamAssistantService(
            @Qualifier("examChatClient") ChatClient examChatClient,
            @Qualifier("examToolCallbacks") ToolCallback[] examToolCallbacks,
            ExamLectureTools lectureTools,
            ExamPipelineService pipelineService,
            @Value("${app.openrouter.api-key:}") String apiKey,
            @Value("${app.openrouter.model}") String openRouterModel,
            @Value("${app.agent.temperature:0.5}") double temperature,
            @Value("${app.exam.max-tokens:1600}") int maxTokens) {
        this.examChatClient = examChatClient;
        this.examToolCallbacks = examToolCallbacks;
        this.lectureTools = lectureTools;
        this.pipelineService = pipelineService;
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.openRouterModel = openRouterModel;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    public boolean cloudConfigured() {
        return pipelineService.cloudConfigured();
    }

    public ExamAnswer answer(String question) {
        long started = System.currentTimeMillis();
        String q = question != null ? question.trim() : "";
        if (q.isBlank()) {
            throw new IllegalArgumentException("question is required");
        }
        if (!cloudConfigured()) {
            throw new IllegalStateException("OPENROUTER_API_KEY не задан — exam assistant недоступен.");
        }

        AgentChatClientConfig.beginToolCallRecording();
        ExamLectureTools.beginRecording();

        List<McpToolCallLogDto> prefetched = new ArrayList<>();
        long prefetchStarted = System.currentTimeMillis();
        String lectureContext = lectureTools.retrieveExamLecture(q);
        prefetched.add(new McpToolCallLogDto(
                ExamLectureTools.TOOL_SERVER_NAME,
                "retrieveExamLecture",
                "{\"query\":\"" + q.replace("\"", "'") + "\"}",
                lectureContext != null && lectureContext.length() > 200
                        ? lectureContext.substring(0, 200) + "…"
                        : lectureContext,
                System.currentTimeMillis() - prefetchStarted));

        String userMessage = """
                Экзаменационный вопрос (ищи по всем загруженным лекциям):
                %s

                --- Фрагменты лекций (уже загружено) ---
                %s
                """.formatted(
                q,
                lectureContext == null || lectureContext.isBlank() ? "(пусто)" : lectureContext);

        String answerText;
        String model = openRouterModel;
        try {
            ChatOptions options = OpenAiChatOptions.builder()
                    .model(openRouterModel)
                    .temperature(temperature)
                    .maxTokens(maxTokens)
                    .build();

            ChatResponse chatResponse = examChatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userMessage)
                    .toolCallbacks(examToolCallbacks)
                    .options(options)
                    .call()
                    .chatResponse();

            answerText = chatResponse != null && chatResponse.getResult() != null
                    ? chatResponse.getResult().getOutput().getText()
                    : "";
            if (answerText == null || answerText.isBlank()) {
                answerText = "Не удалось сформировать ответ. Загрузите лекцию и дождитесь индексации.";
            } else {
                answerText = answerText.trim();
            }
            if (chatResponse != null
                    && chatResponse.getMetadata() != null
                    && chatResponse.getMetadata().getModel() != null) {
                model = chatResponse.getMetadata().getModel();
            }
        } catch (Exception exception) {
            log.warn("Exam assistant failed: {}", exception.getMessage());
            answerText = "Ошибка exam assistant: " + exception.getMessage();
        }

        List<McpToolCallLogDto> extra = AgentChatClientConfig.drainToolCallRecording();
        List<String> sources = ExamLectureTools.drainSources();
        List<ExamCitationDto> citations = ExamLectureTools.drainCitations().stream()
                .map(c -> new ExamCitationDto(c.lecture(), c.timestamp(), c.quote(), c.score()))
                .toList();
        List<McpToolCallLogDto> allTools = new ArrayList<>(prefetched);
        allTools.addAll(extra);

        long durationMs = System.currentTimeMillis() - started;
        boolean cited = !citations.isEmpty() && answerText.contains("@");
        log.info("Exam answered in {} ms, sources={}, cited={}", durationMs, sources.size(), cited);
        return new ExamAnswer(answerText, model, durationMs, sources, citations, allTools, cited);
    }

    public record ExamAnswer(
            String answer,
            String model,
            long durationMs,
            List<String> sources,
            List<ExamCitationDto> citations,
            List<McpToolCallLogDto> toolCalls,
            boolean trustCited) {
    }
}

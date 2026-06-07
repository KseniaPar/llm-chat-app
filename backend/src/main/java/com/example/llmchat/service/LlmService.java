package com.example.llmchat.service;

import com.example.llmchat.dto.CompareResult;
import com.example.llmchat.dto.LlmResult;
import com.example.llmchat.dto.ModelAnalysisRequest;
import com.example.llmchat.dto.ModelCallResponse;
import com.example.llmchat.dto.ModelCompareResult;
import com.example.llmchat.dto.ModelMetrics;
import com.example.llmchat.dto.ModelTierResult;
import com.example.llmchat.dto.ReasoningCompareResult;
import com.example.llmchat.dto.TemperatureAnalysisRequest;
import com.example.llmchat.dto.TemperatureCompareResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final String FORMAT_ONLY_PROMPT = """
            Ответь строго в формате:
            Тезис: <одно предложение>
            Факт: <одно предложение>
            """;
    private static final String STOP_ONLY_PROMPT = """
            Заверши ответ строкой END на отдельной строке.
            """;
    private static final String FULL_CONTROL_PROMPT = """
            Ответь строго в формате:
            Тезис: <одно предложение>
            Факт: <одно предложение>
            Заверши ответ строкой END на отдельной строке.
            """;
    private static final int LENGTH_MAX_TOKENS = 80;
    private static final int FULL_MAX_TOKENS = 80;
    private static final List<String> STOP_SEQUENCES = List.of("END");

    private static final String STEP_BY_STEP_PROMPT = """
            Решай задачу пошагово. Покажи каждый шаг рассуждения и в конце дай финальный ответ.
            """;
    private static final String META_PROMPT_GENERATION = """
            Составь промпт для решения следующей логической задачи.
            Верни только текст промпта, без решения задачи.
            """;
    private static final String EXPERTS_PROMPT = """
            Ты — модератор панели экспертов. Три эксперта по очереди решают одну логическую задачу:
            1. Аналитик — разбирает условия, выделяет факты и ограничения.
            2. Инженер — строит пошаговое решение на основе анализа.
            3. Критик — проверяет логику решения инженера, указывает ошибки или подтверждает верность.
            Каждый эксперт даёт своё решение с финальным ответом.
            Оформи ответ с заголовками: Аналитик, Инженер, Критик.
            """;
    private static final String COMPARISON_PROMPT = """
            Сравни 4 решения одной логической задачи разными методами.
            Укажи:
            1) Отличаются ли финальные ответы между методами?
            2) Какой метод дал наиболее точный результат и почему?
            3) Краткий вывод: какой способ рассуждения эффективнее для логических задач.
            """;
    private static final String TEMPERATURE_SYSTEM_PROMPT = """
            Отвечай кратко: не более 5–7 предложений или 3–4 пункта списка.
            Без вступлений, заключений и повторов. Сразу по сути.
            """;
    private static final String TEMPERATURE_COMPARISON_PROMPT = """
            Сравни 3 ответа на один запрос (temperature 0, 0.7, 1.2).
            Кратко оцени: точность, креативность, разнообразие.
            В конце — по одному предложению: когда использовать каждую temperature.
            Не более 8–10 предложений всего.
            """;
    private static final int TEMPERATURE_MAX_TOKENS = 180;
    private static final int TEMPERATURE_COMPARISON_MAX_TOKENS = 220;
    private static final double TEMP_0 = 0.0;
    private static final double TEMP_07 = 0.7;
    private static final double TEMP_12 = 1.2;

    private static final String MODEL_SYSTEM_PROMPT = """
            Отвечай кратко: не более 5–7 предложений или 3–4 пункта списка.
            Без вступлений, заключений и повторов. Сразу по сути.
            """;
    private static final String MODEL_COMPARISON_PROMPT = """
            Сравни 3 ответа на один запрос от моделей разного размера (слабая/средняя/сильная).
            Оцени: качество, точность, полноту, скорость (по метрикам), ресурсоёмкость (токены).
            В конце — короткий вывод (3–5 предложений): когда какую модель выбирать.
            Не более 10–12 предложений всего.
            """;
    private static final int MODEL_COMPARISON_MAX_TOKENS = 250;
    private static final Pattern RETRY_AFTER_SECONDS = Pattern.compile("retry_after_seconds(?:_raw)?\":([0-9.]+)");

    private final ChatModel chatModel;
    private final CompareRequestLogger compareLogger;
    private final String baseUrl;
    private final String model;
    private final String weakModel;
    private final String mediumModel;
    private final String strongModel;
    private final int modelMaxTokens;
    private final double modelTemperature;

    public LlmService(
            ChatModel chatModel,
            CompareRequestLogger compareLogger,
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.chat.options.model}") String model,
            @Value("${app.compare-models.weak}") String weakModel,
            @Value("${app.compare-models.medium}") String mediumModel,
            @Value("${app.compare-models.strong}") String strongModel,
            @Value("${app.compare-models.max-tokens}") int modelMaxTokens,
            @Value("${app.compare-models.temperature}") double modelTemperature) {
        this.chatModel = chatModel;
        this.compareLogger = compareLogger;
        this.baseUrl = baseUrl;
        this.model = model;
        this.weakModel = weakModel;
        this.mediumModel = mediumModel;
        this.strongModel = strongModel;
        this.modelMaxTokens = modelMaxTokens;
        this.modelTemperature = modelTemperature;
    }

    public LlmResult ask(String prompt) {
        List<String> logs = new ArrayList<>();
        addLog(logs, "Получен промпт от клиента: \"" + prompt + "\"");

        String answer = callAndLog(
                logs,
                "Запрос в OpenRouter",
                List.of(new UserMessage(prompt)),
                null);

        return new LlmResult(answer, logs);
    }

    public CompareResult compare(String prompt) {
        String llmUrl = baseUrl + "/v1/chat/completions";
        StringBuilder logs = new StringBuilder();
        logs.append(compareLogger.logCompareStart(prompt));

        List<Message> unrestrictedMessages = List.of(new UserMessage(prompt));
        String unrestricted = runCompareRequest(
                logs, "Request 1: UNRESTRICTED", llmUrl, unrestrictedMessages, null);

        List<Message> formatOnlyMessages = List.of(
                new SystemMessage(FORMAT_ONLY_PROMPT),
                new UserMessage(prompt));
        String formatOnly = runCompareRequest(
                logs, "Request 2: FORMAT ONLY", llmUrl, formatOnlyMessages, null);

        OpenAiChatOptions lengthOptions = OpenAiChatOptions.builder()
                .maxTokens(LENGTH_MAX_TOKENS)
                .build();
        List<Message> lengthOnlyMessages = List.of(new UserMessage(prompt));
        String lengthOnly = runCompareRequest(
                logs, "Request 3: LENGTH ONLY", llmUrl, lengthOnlyMessages, lengthOptions);

        OpenAiChatOptions stopOptions = OpenAiChatOptions.builder()
                .stop(STOP_SEQUENCES)
                .build();
        List<Message> stopOnlyMessages = List.of(
                new SystemMessage(STOP_ONLY_PROMPT),
                new UserMessage(prompt));
        String stopOnly = runCompareRequest(
                logs, "Request 4: STOP ONLY", llmUrl, stopOnlyMessages, stopOptions);

        OpenAiChatOptions fullOptions = OpenAiChatOptions.builder()
                .maxTokens(FULL_MAX_TOKENS)
                .stop(STOP_SEQUENCES)
                .build();
        List<Message> fullControlMessages = List.of(
                new SystemMessage(FULL_CONTROL_PROMPT),
                new UserMessage(prompt));
        String fullControl = runCompareRequest(
                logs, "Request 5: FULL CONTROL", llmUrl, fullControlMessages, fullOptions);

        logs.append(compareLogger.logCompareSummary(
                unrestricted.length(),
                formatOnly.length(),
                lengthOnly.length(),
                stopOnly.length(),
                fullControl.length()));

        return new CompareResult(unrestricted, formatOnly, lengthOnly, stopOnly, fullControl, logs.toString());
    }

    public LlmResult askWithTemperature(String prompt, double temperature) {
        List<String> logs = new ArrayList<>();
        String answer = callAndLog(
                logs,
                "Запрос в OpenRouter (temperature = " + temperature + ")",
                temperatureMessages(prompt),
                buildTemperatureOptions(temperature));
        return new LlmResult(answer, logs);
    }

    public LlmResult analyzeTemperature(TemperatureAnalysisRequest request) {
        String llmUrl = baseUrl + "/v1/chat/completions";
        StringBuilder logs = new StringBuilder();

        String comparisonInput = """
                Запрос: %s

                --- temperature = 0 ---
                %s

                --- temperature = 0.7 ---
                %s

                --- temperature = 1.2 ---
                %s
                """.formatted(
                request.prompt(),
                request.temp0(),
                request.temp07(),
                request.temp12());

        List<Message> comparisonMessages = List.of(
                new SystemMessage(TEMPERATURE_COMPARISON_PROMPT),
                new UserMessage(comparisonInput));
        OpenAiChatOptions comparisonOptions = OpenAiChatOptions.builder()
                .maxTokens(TEMPERATURE_COMPARISON_MAX_TOKENS)
                .build();
        String comparison = runCompareRequest(
                logs, "Request: AUTO COMPARISON", llmUrl, comparisonMessages, comparisonOptions);

        return new LlmResult(comparison, List.of(logs.toString()));
    }

    public TemperatureCompareResult compareTemperature(String prompt) {
        String llmUrl = baseUrl + "/v1/chat/completions";
        StringBuilder logs = new StringBuilder();
        logs.append(compareLogger.logTemperatureCompareStart(prompt));

        List<Message> messages = temperatureMessages(prompt);

        CompletableFuture<TemperatureRequestResult> temp0Future = CompletableFuture.supplyAsync(
                () -> runTemperatureRequest(
                        llmUrl, messages, TEMP_0, "Request 1: TEMPERATURE = 0"));
        CompletableFuture<TemperatureRequestResult> temp07Future = CompletableFuture.supplyAsync(
                () -> runTemperatureRequest(
                        llmUrl, messages, TEMP_07, "Request 2: TEMPERATURE = 0.7"));
        CompletableFuture<TemperatureRequestResult> temp12Future = CompletableFuture.supplyAsync(
                () -> runTemperatureRequest(
                        llmUrl, messages, TEMP_12, "Request 3: TEMPERATURE = 1.2"));

        TemperatureRequestResult temp0Result = temp0Future.join();
        TemperatureRequestResult temp07Result = temp07Future.join();
        TemperatureRequestResult temp12Result = temp12Future.join();

        logs.append(temp0Result.logs());
        logs.append(temp07Result.logs());
        logs.append(temp12Result.logs());

        String temp0 = temp0Result.answer();
        String temp07 = temp07Result.answer();
        String temp12 = temp12Result.answer();

        LlmResult comparisonResult = analyzeTemperature(
                new TemperatureAnalysisRequest(prompt, temp0, temp07, temp12));
        String comparison = comparisonResult.response();
        logs.append(comparisonResult.logs().get(0));

        logs.append(compareLogger.logTemperatureSummary(
                temp0.length(),
                temp07.length(),
                temp12.length(),
                comparison.length()));

        return new TemperatureCompareResult(temp0, temp07, temp12, comparison, logs.toString());
    }

    public ModelCallResponse askWithModel(String prompt, String tier) {
        String modelId = resolveModelId(tier);
        List<String> logs = new ArrayList<>();
        addLog(logs, "Запрос в OpenRouter (tier = " + tier + ", model = " + modelId + ")");

        List<Message> messages = modelMessages(prompt);
        OpenAiChatOptions options = buildModelOptions(modelId);
        LlmCallResult result = callLlmWithMetrics(messages, options, modelId);

        addLog(logs, "Время ответа: " + result.metrics().responseTimeMs() + " ms");
        addLog(logs, "Токены: prompt=" + result.metrics().promptTokens()
                + ", completion=" + result.metrics().completionTokens()
                + ", total=" + result.metrics().totalTokens());
        addLog(logs, "Стоимость: $" + String.format("%.6f", result.metrics().costUsd()));
        addLog(logs, "Ответ: \"" + result.text() + "\"");

        return new ModelCallResponse(result.text(), result.metrics(), logs);
    }

    public LlmResult analyzeModels(ModelAnalysisRequest request) {
        String llmUrl = baseUrl + "/v1/chat/completions";
        StringBuilder logs = new StringBuilder();

        String comparisonInput = buildModelComparisonInput(request);
        List<Message> comparisonMessages = List.of(
                new SystemMessage(MODEL_COMPARISON_PROMPT),
                new UserMessage(comparisonInput));
        OpenAiChatOptions comparisonOptions = OpenAiChatOptions.builder()
                .model(mediumModel)
                .maxTokens(MODEL_COMPARISON_MAX_TOKENS)
                .temperature(modelTemperature)
                .build();

        LlmCallResult result = callLlmWithMetrics(comparisonMessages, comparisonOptions, mediumModel);
        logs.append(compareLogger.logModelRequest(
                "Request: AUTO COMPARISON",
                llmUrl,
                mediumModel,
                comparisonMessages,
                comparisonOptions,
                result.text(),
                result.metrics()));

        return new LlmResult(result.text(), List.of(logs.toString()));
    }

    public ModelCompareResult compareModels(String prompt) {
        String llmUrl = baseUrl + "/v1/chat/completions";
        StringBuilder logs = new StringBuilder();
        logs.append(compareLogger.logModelCompareStart(prompt));

        List<Message> messages = modelMessages(prompt);

        ModelRequestResult weakResult = runModelRequest(llmUrl, messages, weakModel, "Request 1: WEAK MODEL");
        ModelRequestResult mediumResult = runModelRequest(llmUrl, messages, mediumModel, "Request 2: MEDIUM MODEL");
        ModelRequestResult strongResult = runModelRequest(llmUrl, messages, strongModel, "Request 3: STRONG MODEL");

        logs.append(weakResult.logs());
        logs.append(mediumResult.logs());
        logs.append(strongResult.logs());

        ModelTierResult weak = new ModelTierResult(weakResult.answer(), weakResult.metrics());
        ModelTierResult medium = new ModelTierResult(mediumResult.answer(), mediumResult.metrics());
        ModelTierResult strong = new ModelTierResult(strongResult.answer(), strongResult.metrics());

        LlmResult comparisonResult = analyzeModels(new ModelAnalysisRequest(
                prompt,
                weak.response(),
                medium.response(),
                strong.response(),
                weak.metrics(),
                medium.metrics(),
                strong.metrics()));
        String comparison = comparisonResult.response();
        logs.append(comparisonResult.logs().get(0));

        logs.append(compareLogger.logModelSummary(
                weak.metrics(),
                medium.metrics(),
                strong.metrics(),
                comparison.length()));

        return new ModelCompareResult(weak, medium, strong, comparison, logs.toString());
    }

    private ModelRequestResult runModelRequest(
            String llmUrl,
            List<Message> messages,
            String modelId,
            String label) {
        StringBuilder logs = new StringBuilder();
        OpenAiChatOptions options = buildModelOptions(modelId);
        LlmCallResult result = callLlmWithMetrics(messages, options, modelId);
        logs.append(compareLogger.logModelRequest(
                label, llmUrl, modelId, messages, options, result.text(), result.metrics()));
        return new ModelRequestResult(result.text(), result.metrics(), logs.toString());
    }

    private String buildModelComparisonInput(ModelAnalysisRequest request) {
        return """
                Запрос: %s

                --- Слабая модель (%s) ---
                %s
                %s

                --- Средняя модель (%s) ---
                %s
                %s

                --- Сильная модель (%s) ---
                %s
                %s
                """.formatted(
                request.prompt(),
                metricsModelId(request.weakMetrics(), "слабая"),
                formatMetricsLine(request.weakMetrics()),
                request.weak(),
                metricsModelId(request.mediumMetrics(), "средняя"),
                formatMetricsLine(request.mediumMetrics()),
                request.medium(),
                metricsModelId(request.strongMetrics(), "сильная"),
                formatMetricsLine(request.strongMetrics()),
                request.strong());
    }

    private String metricsModelId(ModelMetrics metrics, String fallback) {
        return metrics != null && metrics.modelId() != null ? metrics.modelId() : fallback;
    }

    private String formatMetricsLine(ModelMetrics metrics) {
        if (metrics == null) {
            return "Метрики не предоставлены";
        }
        return "Время: %d ms | Токены: %d (prompt=%d, completion=%d) | Стоимость: $%.6f".formatted(
                metrics.responseTimeMs(),
                metrics.totalTokens(),
                metrics.promptTokens(),
                metrics.completionTokens(),
                metrics.costUsd());
    }

    private List<Message> modelMessages(String prompt) {
        return List.of(
                new SystemMessage(MODEL_SYSTEM_PROMPT),
                new UserMessage(prompt));
    }

    private OpenAiChatOptions buildModelOptions(String modelId) {
        return OpenAiChatOptions.builder()
                .model(modelId)
                .temperature(modelTemperature)
                .maxTokens(modelMaxTokens)
                .build();
    }

    private String resolveModelId(String tier) {
        return switch (tier.toLowerCase()) {
            case "weak" -> weakModel;
            case "medium" -> mediumModel;
            case "strong" -> strongModel;
            default -> throw new IllegalArgumentException("Unknown tier: " + tier + ". Use weak, medium, or strong.");
        };
    }

    private double calculateCost(String modelId, int promptTokens, int completionTokens) {
        if (modelId != null && modelId.endsWith(":free")) {
            return 0.0;
        }
        if (modelId != null && modelId.contains("gpt-4o-mini")) {
            return (promptTokens * 0.15 / 1_000_000.0) + (completionTokens * 0.60 / 1_000_000.0);
        }
        return 0.0;
    }

    private LlmCallResult callLlmWithMetrics(
            List<Message> messages,
            OpenAiChatOptions options,
            String modelId) {
        NonTransientAiException lastError = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                return callLlmOnce(messages, options, modelId);
            } catch (NonTransientAiException exception) {
                lastError = exception;
                if (attempt < 5 && isRateLimited(exception)) {
                    sleepBeforeRetry(exception, attempt);
                    continue;
                }
                throw exception;
            }
        }
        throw lastError;
    }

    private LlmCallResult callLlmOnce(
            List<Message> messages,
            OpenAiChatOptions options,
            String modelId) {
        long start = System.nanoTime();
        Prompt prompt = options != null ? new Prompt(messages, options) : new Prompt(messages);
        ChatResponse chatResponse = chatModel.call(prompt);
        long responseTimeMs = (System.nanoTime() - start) / 1_000_000;

        String text = chatResponse.getResult().getOutput().getText();
        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;

        Usage usage = chatResponse.getMetadata() != null ? chatResponse.getMetadata().getUsage() : null;
        if (usage != null) {
            promptTokens = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
            completionTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
            totalTokens = usage.getTotalTokens() != null ? usage.getTotalTokens() : promptTokens + completionTokens;
        }

        double costUsd = calculateCost(modelId, promptTokens, completionTokens);
        ModelMetrics metrics = new ModelMetrics(
                responseTimeMs, promptTokens, completionTokens, totalTokens, costUsd, modelId);
        return new LlmCallResult(text, metrics);
    }

    private boolean isRateLimited(NonTransientAiException exception) {
        String message = exception.getMessage();
        return message != null && message.contains("429");
    }

    private void sleepBeforeRetry(NonTransientAiException exception, int attempt) {
        long sleepMs = 3000L * attempt;
        Matcher matcher = RETRY_AFTER_SECONDS.matcher(exception.getMessage() != null ? exception.getMessage() : "");
        if (matcher.find()) {
            sleepMs = (long) (Double.parseDouble(matcher.group(1)) * 1000L) + 500L;
        }
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private record LlmCallResult(String text, ModelMetrics metrics) {
    }

    private record ModelRequestResult(String answer, ModelMetrics metrics, String logs) {
    }

    private TemperatureRequestResult runTemperatureRequest(
            String llmUrl,
            List<Message> messages,
            double temperature,
            String label) {
        StringBuilder logs = new StringBuilder();
        String answer = runCompareRequest(
                logs, label, llmUrl, messages, buildTemperatureOptions(temperature));
        return new TemperatureRequestResult(answer, logs.toString());
    }

    private List<Message> temperatureMessages(String prompt) {
        return List.of(
                new SystemMessage(TEMPERATURE_SYSTEM_PROMPT),
                new UserMessage(prompt));
    }

    private OpenAiChatOptions buildTemperatureOptions(double temperature) {
        return OpenAiChatOptions.builder()
                .temperature(temperature)
                .maxTokens(TEMPERATURE_MAX_TOKENS)
                .build();
    }

    private record TemperatureRequestResult(String answer, String logs) {
    }

    public ReasoningCompareResult compareReasoning(String task) {
        String llmUrl = baseUrl + "/v1/chat/completions";
        StringBuilder logs = new StringBuilder();
        logs.append(compareLogger.logReasoningCompareStart(task));

        List<Message> directMessages = List.of(new UserMessage(task));
        String direct = runCompareRequest(
                logs, "Request 1: DIRECT", llmUrl, directMessages, null);

        List<Message> stepByStepMessages = List.of(
                new SystemMessage(STEP_BY_STEP_PROMPT),
                new UserMessage(task));
        String stepByStep = runCompareRequest(
                logs, "Request 2: STEP BY STEP", llmUrl, stepByStepMessages, null);

        List<Message> metaPromptGenMessages = List.of(
                new SystemMessage(META_PROMPT_GENERATION),
                new UserMessage(task));
        String metaPrompt = runCompareRequest(
                logs, "Request 3a: META PROMPT GENERATION", llmUrl, metaPromptGenMessages, null);

        List<Message> metaPromptAnswerMessages = List.of(new UserMessage(metaPrompt));
        String metaPromptAnswer = runCompareRequest(
                logs, "Request 3b: META PROMPT ANSWER", llmUrl, metaPromptAnswerMessages, null);

        List<Message> expertsMessages = List.of(
                new SystemMessage(EXPERTS_PROMPT),
                new UserMessage(task));
        String experts = runCompareRequest(
                logs, "Request 4: EXPERTS PANEL", llmUrl, expertsMessages, null);

        String comparisonInput = """
                Задача: %s

                --- Прямой ответ ---
                %s

                --- Пошагово ---
                %s

                --- Мета-промпт (ответ) ---
                %s

                --- Эксперты ---
                %s
                """.formatted(task, direct, stepByStep, metaPromptAnswer, experts);

        List<Message> comparisonMessages = List.of(
                new SystemMessage(COMPARISON_PROMPT),
                new UserMessage(comparisonInput));
        String comparison = runCompareRequest(
                logs, "Request 5: AUTO COMPARISON", llmUrl, comparisonMessages, null);

        logs.append(compareLogger.logReasoningSummary(
                direct.length(),
                stepByStep.length(),
                metaPrompt.length(),
                metaPromptAnswer.length(),
                experts.length(),
                comparison.length()));

        return new ReasoningCompareResult(
                direct,
                stepByStep,
                metaPrompt,
                metaPromptAnswer,
                experts,
                comparison,
                logs.toString());
    }

    private String runCompareRequest(
            StringBuilder logs,
            String label,
            String llmUrl,
            List<Message> messages,
            OpenAiChatOptions options) {
        String answer = callLlm(messages, options);
        logs.append(compareLogger.logRequest(label, llmUrl, model, messages, options, answer));
        return answer;
    }

    private String callLlm(List<Message> messages, ChatOptions options) {
        String modelId = model;
        if (options instanceof OpenAiChatOptions openAiOptions && openAiOptions.getModel() != null) {
            modelId = openAiOptions.getModel();
        }
        return callLlmWithMetrics(messages, openAiOptionsFrom(options), modelId).text();
    }

    private OpenAiChatOptions openAiOptionsFrom(ChatOptions options) {
        if (options instanceof OpenAiChatOptions openAiOptions) {
            return openAiOptions;
        }
        return null;
    }

    private String callAndLog(
            List<String> logs,
            String label,
            List<Message> messages,
            ChatOptions options) {
        String llmUrl = baseUrl + "/v1/chat/completions";
        addLog(logs, label + " — POST " + llmUrl);
        addLog(logs, "Модель: " + model);
        addLog(logs, "Сообщения: " + formatMessages(messages));

        if (options instanceof OpenAiChatOptions openAiOptions) {
            addLog(logs, "temperature: " + openAiOptions.getTemperature());
            addLog(logs, "max_tokens: " + openAiOptions.getMaxTokens());
            addLog(logs, "stop: " + openAiOptions.getStopSequences());
        } else if (options == null) {
            addLog(logs, "max_tokens: не задан (дефолт модели)");
            addLog(logs, "stop: не задан");
        }

        log.info("{} — sending to {} with model {}", label, llmUrl, model);

        String answer = callLlm(messages, options);

        addLog(logs, "Ответ получен (" + answer.length() + " символов)");
        addLog(logs, "Ответ: \"" + answer + "\"");

        log.info("{} — response ({} chars)", label, answer.length());

        return answer;
    }

    private String formatMessages(List<Message> messages) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Message message = messages.get(i);
            sb.append("{\"role\":\"")
                    .append(message.getMessageType().getValue())
                    .append("\",\"content\":\"")
                    .append(message.getText())
                    .append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    private void addLog(List<String> logs, String message) {
        String entry = "[" + LocalDateTime.now().format(TIME_FORMAT) + "] " + message;
        logs.add(entry);
        log.info(message);
    }
}

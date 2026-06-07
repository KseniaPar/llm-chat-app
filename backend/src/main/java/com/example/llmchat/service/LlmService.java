package com.example.llmchat.service;

import com.example.llmchat.dto.CompareResult;
import com.example.llmchat.dto.LlmResult;
import com.example.llmchat.dto.ReasoningCompareResult;
import com.example.llmchat.dto.TemperatureAnalysisRequest;
import com.example.llmchat.dto.TemperatureCompareResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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

    private final ChatModel chatModel;
    private final CompareRequestLogger compareLogger;
    private final String baseUrl;
    private final String model;

    public LlmService(
            ChatModel chatModel,
            CompareRequestLogger compareLogger,
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.chat.options.model}") String model) {
        this.chatModel = chatModel;
        this.compareLogger = compareLogger;
        this.baseUrl = baseUrl;
        this.model = model;
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
        Prompt prompt = options != null ? new Prompt(messages, options) : new Prompt(messages);
        return chatModel.call(prompt)
                .getResult()
                .getOutput()
                .getText();
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

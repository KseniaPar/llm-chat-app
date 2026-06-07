package com.example.llmchat.service;

import com.example.llmchat.dto.CompareResult;
import com.example.llmchat.dto.LlmResult;
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

package com.example.llmchat.service;

import com.example.llmchat.dto.LlmResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
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

    private final ChatModel chatModel;
    private final String baseUrl;
    private final String model;

    public LlmService(
            ChatModel chatModel,
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.chat.options.model}") String model) {
        this.chatModel = chatModel;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    public LlmResult ask(String prompt) {
        List<String> logs = new ArrayList<>();

        addLog(logs, "Получен промпт от клиента: \"" + prompt + "\"");

        String llmUrl = baseUrl + "/v1/chat/completions";
        addLog(logs, "Отправка запроса в OpenRouter: POST " + llmUrl);
        addLog(logs, "Модель: " + model);
        addLog(logs, "Тело запроса: {\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"" + prompt + "\"}]}");

        log.info("Sending LLM request to {} with model {}", llmUrl, model);
        log.info("Prompt: {}", prompt);

        String answer = chatModel.call(new Prompt(prompt))
                .getResult()
                .getOutput()
                .getText();

        addLog(logs, "Ответ от LLM получен (" + answer.length() + " символов)");
        addLog(logs, "Ответ: \"" + answer + "\"");

        log.info("LLM response ({} chars): {}", answer.length(), answer);

        return new LlmResult(answer, logs);
    }

    private void addLog(List<String> logs, String message) {
        String entry = "[" + LocalDateTime.now().format(TIME_FORMAT) + "] " + message;
        logs.add(entry);
        log.info(message);
    }
}

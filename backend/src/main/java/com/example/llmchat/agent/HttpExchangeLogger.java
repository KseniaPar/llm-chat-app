package com.example.llmchat.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class HttpExchangeLogger {

    private static final Logger log = LoggerFactory.getLogger(HttpExchangeLogger.class);

    private final ObjectMapper objectMapper;

    public HttpExchangeLogger(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void logAgentContext(String sessionId, int historySize, String userPrompt) {
        log.info(
                "\nАгент\n  Session : {}\n  History : {} сообщ.\n  Prompt  : \"{}\"",
                sessionId,
                historySize,
                LogText.truncate(userPrompt).replace("\"", "\\\""));
    }

    public void logTokenEstimate(int currentPromptTokens, int historyTokens, int requestTokens, int contextLimit) {
        log.info(
                "\nТокены (оценка до запроса)\n  Текущий запрос : ~{}\n  История        : ~{}\n  Весь промпт    : ~{} / {}\n  Остаток окна   : ~{}",
                currentPromptTokens,
                historyTokens,
                requestTokens,
                contextLimit,
                Math.max(0, contextLimit - requestTokens));
    }

    public void logTokenUsage(int promptTokens, int completionTokens, int totalTokens, double costUsd) {
        log.info(
                "\nТокены (факт API)\n  Prompt     : {}\n  Completion : {}\n  Total      : {}\n  Стоимость  : ${}",
                promptTokens,
                completionTokens,
                totalTokens,
                String.format("%.6f", costUsd));
    }

    public void logRequest(String method, String url, HttpHeaders headers, Object body) {
        log.info(
                "\nOpenRouter → запрос\n  {} {}\n{}\n\n  Body:\n{}",
                method,
                url,
                formatHeaders(headers),
                prettyJson(sanitizeBodyForLog(body)));
    }

    public void logResponse(int statusCode, HttpHeaders headers, String body) {
        log.info(
                "\nOpenRouter ← ответ\n  HTTP {}\n{}\n\n  Body:\n{}",
                statusCode,
                formatHeaders(headers),
                prettyJson(body));
    }

    private String formatHeaders(HttpHeaders headers) {
        if (headers == null || headers.isEmpty()) {
            return "  Headers:\n    (пусто)";
        }

        StringBuilder sb = new StringBuilder("  Headers:");
        headers.forEach((name, values) -> {
            for (String value : values) {
                sb.append("\n    ")
                        .append(name)
                        .append(": ")
                        .append(maskIfSensitive(name, value));
            }
        });
        return sb.toString();
    }

    private String maskIfSensitive(String headerName, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (!"authorization".equalsIgnoreCase(headerName)) {
            return value;
        }
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return "Bearer ••••••••";
        }
        return "••••••••";
    }

    private String prettyJson(Object value) {
        try {
            if (value instanceof String raw) {
                return indent(prettyJsonString(raw));
            }
            return indent(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
        } catch (JsonProcessingException exception) {
            return indent(String.valueOf(value));
        }
    }

    private String prettyJsonString(String raw) throws JsonProcessingException {
        if (raw == null || raw.isBlank()) {
            return "(пусто)";
        }
        JsonNode node = objectMapper.readTree(raw);
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }

    private Object sanitizeBodyForLog(Object body) {
        if (!(body instanceof Map<?, ?> rawMap)) {
            return body;
        }

        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if ("messages".equals(key) && value instanceof List<?> messages) {
                sanitized.put(key, messages.stream()
                        .map(this::sanitizeMessageForLog)
                        .toList());
            } else {
                sanitized.put(key, value);
            }
        }
        return sanitized;
    }

    private Object sanitizeMessageForLog(Object message) {
        if (!(message instanceof Map<?, ?> rawMessage)) {
            return message;
        }

        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMessage.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if ("content".equals(key) && value instanceof String content) {
                sanitized.put(key, LogText.truncate(content));
            } else {
                sanitized.put(key, value);
            }
        }
        return sanitized;
    }

    private String indent(String text) {
        return text.lines().map(line -> "    " + line).collect(Collectors.joining("\n"));
    }
}

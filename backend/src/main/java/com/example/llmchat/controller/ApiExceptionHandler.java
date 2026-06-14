package com.example.llmchat.controller;

import com.example.llmchat.agent.OpenRouterHttpException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Pattern RAW_MESSAGE = Pattern.compile("\"raw\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern BODY_PREFIX = Pattern.compile("^\\d+\\s*—\\s*");

    private final ObjectMapper objectMapper;

    public ApiExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @ExceptionHandler(OpenRouterHttpException.class)
    public ResponseEntity<Map<String, Object>> handleOpenRouterHttpException(OpenRouterHttpException exception) {
        String rawBody = extractResponseBody(exception.getMessage());
        String message = extractReadableMessage(rawBody != null ? rawBody : exception.getMessage());
        HttpStatus status = resolveOpenRouterStatus(exception.statusCode());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("openRouterError", true);
        body.put("statusCode", exception.statusCode());
        if (rawBody != null) {
            body.put("rawError", rawBody.length() > 2000 ? rawBody.substring(0, 2000) + "..." : rawBody);
        }
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException exception) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", exception.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    private HttpStatus resolveOpenRouterStatus(int statusCode) {
        return switch (statusCode) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            case 402 -> HttpStatus.PAYMENT_REQUIRED;
            case 404 -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.BAD_GATEWAY;
        };
    }

    private String extractResponseBody(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = BODY_PREFIX.matcher(message);
        if (matcher.find()) {
            return message.substring(matcher.end()).trim();
        }
        return message;
    }

    private String extractReadableMessage(String message) {
        String fromJson = extractOpenRouterErrorMessage(message);
        if (fromJson != null && !fromJson.isBlank()) {
            return fromJson;
        }

        Matcher matcher = RAW_MESSAGE.matcher(message);
        if (matcher.find()) {
            return matcher.group(1);
        }
        if (message.contains("No endpoints found")) {
            return "Модель недоступна на OpenRouter. Проверьте ID модели в application.yml.";
        }
        if (message.contains("429")) {
            return "Лимит запросов OpenRouter (429). Подождите 10–30 секунд и попробуйте снова.";
        }
        if (message.contains("402") || message.contains("requires more credits")) {
            return "Недостаточно кредитов OpenRouter (402). Уменьшите max-tokens в application.yml или пополните баланс на openrouter.ai/settings/credits.";
        }
        return message.length() > 800 ? message.substring(0, 800) + "..." : message;
    }

    private String extractOpenRouterErrorMessage(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(message);
            JsonNode errorNode = root.path("error");
            if (!errorNode.isMissingNode()) {
                String errorMessage = errorNode.path("message").asText(null);
                if (errorMessage != null && !errorMessage.isBlank()) {
                    return errorMessage;
                }
            }
            String topLevelMessage = root.path("message").asText(null);
            if (topLevelMessage != null && !topLevelMessage.isBlank()) {
                return topLevelMessage;
            }
        } catch (Exception ignored) {
            // not JSON — fall through to regex / raw text
        }
        return null;
    }
}

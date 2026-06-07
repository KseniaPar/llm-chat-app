package com.example.llmchat.controller;

import org.springframework.ai.retry.NonTransientAiException;
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

    @ExceptionHandler(NonTransientAiException.class)
    public ResponseEntity<Map<String, String>> handleAiException(NonTransientAiException exception) {
        String message = exception.getMessage() != null ? exception.getMessage() : "OpenRouter error";
        HttpStatus status = resolveStatus(message);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", extractReadableMessage(message));
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException exception) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", exception.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    private HttpStatus resolveStatus(String message) {
        if (message.contains("429")) {
            return HttpStatus.TOO_MANY_REQUESTS;
        }
        if (message.contains("404")) {
            return HttpStatus.BAD_GATEWAY;
        }
        return HttpStatus.BAD_GATEWAY;
    }

    private String extractReadableMessage(String message) {
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
        return message.length() > 300 ? message.substring(0, 300) + "..." : message;
    }
}

package com.example.llmchat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * Блокирует запуск на VPS с небезопасными секретами по умолчанию.
 */
@Component
@Profile("vps")
public class VpsStartupValidator {

    private static final String DEFAULT_JWT_PLACEHOLDER = "change-me-use-openssl-rand-base64-32";
    private static final String DEFAULT_API_KEY_PLACEHOLDER = "change-me-private-llm-key";

    private final String jwtSecret;
    private final String localLlmApiKey;

    public VpsStartupValidator(
            @Value("${app.auth.jwt-secret}") String jwtSecret,
            @Value("${app.local-llm.service.api-key:}") String localLlmApiKey) {
        this.jwtSecret = jwtSecret == null ? "" : jwtSecret.trim();
        this.localLlmApiKey = localLlmApiKey == null ? "" : localLlmApiKey.trim();
    }

    @PostConstruct
    void validateSecrets() {
        List<String> problems = new ArrayList<>();

        if (jwtSecret.isBlank()
                || jwtSecret.equals(DEFAULT_JWT_PLACEHOLDER)
                || jwtSecret.length() < 32) {
            problems.add("JWT_SECRET — задайте длинный случайный ключ (bash deploy/vps/setup-env.sh)");
        }
        if (localLlmApiKey.isBlank()
                || localLlmApiKey.equals(DEFAULT_API_KEY_PLACEHOLDER)
                || localLlmApiKey.length() < 16) {
            problems.add("LOCAL_LLM_API_KEY — задайте случайный ключ (bash deploy/vps/setup-env.sh)");
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Небезопасная конфигурация VPS. Исправьте deploy/vps/.env:\n- "
                            + String.join("\n- ", problems));
        }
    }
}

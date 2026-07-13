package com.example.llmchat.localllm;

import com.example.llmchat.dto.LocalLlmChatResponse;
import com.example.llmchat.dto.LocalLlmServiceChatResponse;
import com.example.llmchat.dto.LocalLlmServiceInfoResponse;
import com.example.llmchat.dto.LocalLlmServiceVerifyCheckDto;
import com.example.llmchat.dto.LocalLlmServiceVerifyResponse;
import com.example.llmchat.dto.LocalLlmStatusResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class LocalLlmPrivateService {

    private static final String CHAT_PATH = "/api/local-llm/service/chat";
    private static final String STATUS_PATH = "/api/local-llm/service/info";
    private static final String VERIFY_PATH = "/api/local-llm/service/verify";

    private final LocalLlmService localLlmService;
    private final LocalLlmRateLimiter rateLimiter;
    private final OrthodoxTopicGuard topicGuard;
    private final boolean enabled;
    private final String apiKey;
    private final String systemPrompt;
    private final int maxPromptChars;
    private final int maxConcurrentRequests;
    private final int contextWindow;
    private final int serviceMaxTokens;
    private final double serviceTemperature;
    private final Semaphore concurrentSemaphore;

    public LocalLlmPrivateService(
            LocalLlmService localLlmService,
            LocalLlmRateLimiter rateLimiter,
            OrthodoxTopicGuard topicGuard,
            @Value("${app.local-llm.service.enabled:true}") boolean enabled,
            @Value("${app.local-llm.service.api-key:}") String apiKey,
            @Value("${app.local-llm.service.system-prompt:}") String systemPrompt,
            @Value("${app.local-llm.service.max-prompt-chars:8000}") int maxPromptChars,
            @Value("${app.local-llm.service.max-concurrent-requests:2}") int maxConcurrentRequests,
            @Value("${app.local-llm.service.context-window:8192}") int contextWindow,
            @Value("${app.local-llm.service.max-tokens:160}") int serviceMaxTokens,
            @Value("${app.local-llm.service.temperature:0.3}") double serviceTemperature) {
        this.localLlmService = localLlmService;
        this.rateLimiter = rateLimiter;
        this.topicGuard = topicGuard;
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt.trim();
        this.maxPromptChars = Math.max(256, maxPromptChars);
        this.maxConcurrentRequests = Math.max(1, maxConcurrentRequests);
        this.contextWindow = Math.max(512, contextWindow);
        this.serviceMaxTokens = Math.max(32, serviceMaxTokens);
        this.serviceTemperature = Math.max(0.0, Math.min(2.0, serviceTemperature));
        this.concurrentSemaphore = new Semaphore(this.maxConcurrentRequests, true);
    }

    public LocalLlmServiceInfoResponse info() {
        LocalLlmStatusResponse status = localLlmService.checkStatus();
        boolean online = status.online() && status.modelAvailable();
        String message = online
                ? "Спроси про православие — локальный AI на Ollama."
                : status.message();

        return new LocalLlmServiceInfoResponse(
                status.online(),
                status.modelAvailable(),
                localLlmService.model(),
                status.baseUrl(),
                CHAT_PATH,
                STATUS_PATH,
                VERIFY_PATH,
                rateLimiter.limitPerMinute(),
                maxPromptChars,
                maxConcurrentRequests,
                contextWindow,
                !apiKey.isBlank(),
                message);
    }

    public LocalLlmServiceChatResponse chat(String prompt, String clientKey, String providedApiKey) {
        ensureEnabled();
        validateApiKey(providedApiKey);
        validatePrompt(prompt);

        int rateLimitRemaining = rateLimiter.acquire(clientKey);
        boolean acquired = false;
        try {
            acquired = concurrentSemaphore.tryAcquire(120, TimeUnit.SECONDS);
            if (!acquired) {
                throw new IllegalStateException(
                        "Сервис перегружен: превышен лимит одновременных запросов. Повторите позже.");
            }

            if (topicGuard.isOffTopic(prompt)) {
                return new LocalLlmServiceChatResponse(
                        prompt.trim(),
                        OrthodoxTopicGuard.REFUSAL_MESSAGE,
                        localLlmService.model(),
                        0,
                        0,
                        prompt.trim().length(),
                        rateLimitRemaining);
            }

            LocalLlmChatResponse response = systemPrompt.isBlank()
                    ? localLlmService.chatWithSystem(null, prompt, serviceTemperature, serviceMaxTokens)
                    : localLlmService.chatWithSystem(
                            systemPrompt, prompt, serviceTemperature, serviceMaxTokens);
            return new LocalLlmServiceChatResponse(
                    response.prompt(),
                    response.answer(),
                    response.model(),
                    response.durationMs(),
                    response.evalCount(),
                    prompt.trim().length(),
                    rateLimitRemaining);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Запрос прерван при ожидании слота.");
        } finally {
            if (acquired) {
                concurrentSemaphore.release();
            }
        }
    }

    public LocalLlmServiceVerifyResponse verify() {
        ensureEnabled();
        List<LocalLlmServiceVerifyCheckDto> checks = new ArrayList<>();
        checks.add(checkNetworkAccess());
        checks.add(checkSingleChat());
        checks.add(checkSequentialStability());
        checks.add(checkMaxContextLimit());
        checks.add(checkRateLimitConfig());

        boolean allPassed = checks.stream().allMatch(LocalLlmServiceVerifyCheckDto::passed);
        long failed = checks.stream().filter(check -> !check.passed()).count();
        String summary = allPassed
                ? "Все проверки пройдены: сеть, чат, стабильность, лимиты."
                : "Провалено проверок: " + failed + " из " + checks.size() + ".";

        return new LocalLlmServiceVerifyResponse(allPassed, List.copyOf(checks), summary);
    }

    public void validateApiKey(String providedApiKey) {
        if (apiKey.isBlank()) {
            return;
        }
        if (providedApiKey == null || providedApiKey.isBlank() || !apiKey.equals(providedApiKey.trim())) {
            throw new IllegalArgumentException("Неверный или отсутствующий API-ключ (заголовок X-Local-Llm-Api-Key).");
        }
    }

    private void ensureEnabled() {
        if (!enabled) {
            throw new IllegalStateException("Приватный AI-сервис отключён (app.local-llm.service.enabled=false).");
        }
    }

    private void validatePrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
        int length = prompt.trim().length();
        if (length > maxPromptChars) {
            throw new IllegalArgumentException(
                    "Промпт слишком длинный: " + length + " символов (лимит " + maxPromptChars + ").");
        }
    }

    private LocalLlmServiceVerifyCheckDto checkNetworkAccess() {
        long started = System.currentTimeMillis();
        try {
            LocalLlmStatusResponse status = localLlmService.checkStatus();
            long durationMs = System.currentTimeMillis() - started;
            boolean passed = status.online() && status.modelAvailable();
            String detail = passed
                    ? "Ollama online, модель " + status.configuredModel() + " доступна."
                    : status.message();
            return new LocalLlmServiceVerifyCheckDto(
                    "network", "Доступ к модели по сети", passed, durationMs, detail);
        } catch (Exception exception) {
            return new LocalLlmServiceVerifyCheckDto(
                    "network",
                    "Доступ к модели по сети",
                    false,
                    System.currentTimeMillis() - started,
                    exception.getMessage());
        }
    }

    private LocalLlmServiceVerifyCheckDto checkSingleChat() {
        long started = System.currentTimeMillis();
        try {
            LocalLlmChatResponse response = localLlmService.chatWithSystem(
                    systemPrompt,
                    "Кратко: что такое Великий пост?");
            long durationMs = System.currentTimeMillis() - started;
            boolean passed = response.answer() != null && !response.answer().isBlank();
            return new LocalLlmServiceVerifyCheckDto(
                    "chat",
                    "HTTP API — одиночный чат",
                    passed,
                    durationMs,
                    passed ? "Ответ: " + truncate(response.answer(), 80) : "Пустой ответ");
        } catch (Exception exception) {
            return new LocalLlmServiceVerifyCheckDto(
                    "chat",
                    "HTTP API — одиночный чат",
                    false,
                    System.currentTimeMillis() - started,
                    exception.getMessage());
        }
    }

    private LocalLlmServiceVerifyCheckDto checkSequentialStability() {
        long started = System.currentTimeMillis();
        int successCount = 0;
        String lastError = null;
        String[] prompts = {
                "Что такое исповедь?",
                "Зачем нужен пост?",
                "Сколько таинств в Православной Церкви? Одним числом."
        };

        for (String prompt : prompts) {
            try {
                LocalLlmChatResponse response = localLlmService.chat(prompt);
                if (response.answer() != null && !response.answer().isBlank()) {
                    successCount++;
                } else {
                    lastError = "Пустой ответ на: " + prompt;
                }
            } catch (Exception exception) {
                lastError = exception.getMessage();
            }
        }

        long durationMs = System.currentTimeMillis() - started;
        boolean passed = successCount == prompts.length;
        String detail = passed
                ? "3/3 последовательных запроса успешны за " + durationMs + " ms."
                : "Успешно " + successCount + "/3. " + (lastError != null ? lastError : "");
        return new LocalLlmServiceVerifyCheckDto(
                "stability", "Стабильность при нескольких запросах", passed, durationMs, detail);
    }

    private LocalLlmServiceVerifyCheckDto checkMaxContextLimit() {
        long started = System.currentTimeMillis();
        try {
            String oversized = "x".repeat(maxPromptChars + 1);
            validatePrompt(oversized);
            return new LocalLlmServiceVerifyCheckDto(
                    "max-context",
                    "Ограничение max context",
                    false,
                    System.currentTimeMillis() - started,
                    "Лимит " + maxPromptChars + " не сработал.");
        } catch (IllegalArgumentException exception) {
            return new LocalLlmServiceVerifyCheckDto(
                    "max-context",
                    "Ограничение max context",
                    true,
                    System.currentTimeMillis() - started,
                    "Отклонён промпт " + (maxPromptChars + 1) + " символов (лимит " + maxPromptChars + ").");
        }
    }

    private LocalLlmServiceVerifyCheckDto checkRateLimitConfig() {
        long started = System.currentTimeMillis();
        int limit = rateLimiter.limitPerMinute();
        boolean passed = limit > 0 && limit <= 1000;
        return new LocalLlmServiceVerifyCheckDto(
                "rate-limit",
                "Rate limit",
                passed,
                System.currentTimeMillis() - started,
                "Лимит: " + limit + " запросов/мин на клиента.");
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength) + "…";
    }
}

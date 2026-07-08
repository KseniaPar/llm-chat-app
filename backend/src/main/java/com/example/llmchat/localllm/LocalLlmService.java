package com.example.llmchat.localllm;

import com.example.llmchat.dto.LocalLlmChatResponse;
import com.example.llmchat.dto.LocalLlmStatusResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LocalLlmService {

    private final OllamaHttpClient ollamaHttpClient;
    private final boolean enabled;
    private final String baseUrl;
    private final String model;
    private final double temperature;
    private final int maxTokens;

    public LocalLlmService(
            OllamaHttpClient ollamaHttpClient,
            @Value("${app.local-llm.enabled:true}") boolean enabled,
            @Value("${app.local-llm.base-url}") String baseUrl,
            @Value("${app.local-llm.model}") String model,
            @Value("${app.local-llm.temperature}") double temperature,
            @Value("${app.local-llm.max-tokens}") int maxTokens) {
        this.ollamaHttpClient = ollamaHttpClient;
        this.enabled = enabled;
        this.baseUrl = baseUrl;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    public LocalLlmStatusResponse checkStatus() {
        if (!enabled) {
            return new LocalLlmStatusResponse(
                    false,
                    baseUrl,
                    model,
                    false,
                    List.of(),
                    "Локальная LLM отключена (app.local-llm.enabled=false).");
        }

        if (!ollamaHttpClient.isReachable()) {
            return new LocalLlmStatusResponse(
                    false,
                    baseUrl,
                    model,
                    false,
                    List.of(),
                    "Ollama недоступен. Запустите Ollama и проверьте " + baseUrl);
        }

        List<String> installedModels = ollamaHttpClient.listModels();
        boolean modelAvailable = installedModels.stream().anyMatch(this::matchesConfiguredModel);
        String message = modelAvailable
                ? "Ollama online, модель " + model + " доступна."
                : "Ollama online, но модель " + model + " не найдена. Выполните: ollama pull " + model;

        return new LocalLlmStatusResponse(
                true,
                baseUrl,
                model,
                modelAvailable,
                installedModels,
                message);
    }

    public LocalLlmChatResponse chat(String prompt) {
        if (!enabled) {
            throw new IllegalStateException("Локальная LLM отключена.");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }

        long startedAt = System.currentTimeMillis();
        OllamaHttpClient.ChatResult result = ollamaHttpClient.chat(prompt.trim(), model, temperature, maxTokens);
        long durationMs = System.currentTimeMillis() - startedAt;

        return new LocalLlmChatResponse(
                prompt.trim(),
                result.content(),
                model,
                durationMs,
                result.evalCount());
    }

    public String model() {
        return model;
    }

    public double temperature() {
        return temperature;
    }

    public int maxTokens() {
        return maxTokens;
    }

    public String baseUrl() {
        return baseUrl;
    }

    private boolean matchesConfiguredModel(String installedName) {
        if (installedName == null || installedName.isBlank()) {
            return false;
        }
        if (installedName.equals(model)) {
            return true;
        }
        return installedName.startsWith(model + ":");
    }
}

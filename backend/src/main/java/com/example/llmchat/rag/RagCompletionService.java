package com.example.llmchat.rag;

import com.example.llmchat.agent.CompletionResult;
import com.example.llmchat.agent.OpenRouterHttpClient;
import com.example.llmchat.localllm.LocalLlmService;
import com.example.llmchat.localllm.OllamaHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RagCompletionService {

    private final OpenRouterHttpClient openRouterHttpClient;
    private final OllamaHttpClient ollamaHttpClient;
    private final LocalLlmService localLlmService;
    private final String cloudModel;
    private final double cloudTemperature;
    private final int cloudMaxTokens;
    private final String localModel;
    private final double localTemperature;
    private final int localMaxTokens;

    public RagCompletionService(
            OpenRouterHttpClient openRouterHttpClient,
            OllamaHttpClient ollamaHttpClient,
            LocalLlmService localLlmService,
            @Value("${app.openrouter.model}") String cloudModel,
            @Value("${app.agent.temperature}") double cloudTemperature,
            @Value("${app.agent.max-tokens}") int cloudMaxTokens,
            @Value("${app.local-llm.model}") String localModel,
            @Value("${app.local-llm.temperature}") double localTemperature,
            @Value("${app.rag.local.max-tokens:${app.local-llm.max-tokens}}") int localMaxTokens) {
        this.openRouterHttpClient = openRouterHttpClient;
        this.ollamaHttpClient = ollamaHttpClient;
        this.localLlmService = localLlmService;
        this.cloudModel = cloudModel;
        this.cloudTemperature = cloudTemperature;
        this.cloudMaxTokens = cloudMaxTokens;
        this.localModel = localModel;
        this.localTemperature = localTemperature;
        this.localMaxTokens = localMaxTokens;
    }

    public RagLlmCompletionResult complete(
            List<OpenRouterHttpClient.ChatMessage> messages,
            RagLlmProvider provider) {
        if (provider == RagLlmProvider.LOCAL) {
            return completeLocal(messages);
        }
        return completeCloud(messages);
    }

    public String cloudModel() {
        return cloudModel;
    }

    public String localModel() {
        return localModel;
    }

    private RagLlmCompletionResult completeCloud(List<OpenRouterHttpClient.ChatMessage> messages) {
        long startedAt = System.currentTimeMillis();
        try {
            CompletionResult result = openRouterHttpClient.complete(
                    cloudModel,
                    cloudTemperature,
                    cloudMaxTokens,
                    messages,
                    false);
            long durationMs = System.currentTimeMillis() - startedAt;
            return RagLlmCompletionResult.success(
                    result.content(),
                    RagLlmProvider.CLOUD,
                    cloudModel,
                    durationMs,
                    result.completionTokens());
        } catch (Exception exception) {
            long durationMs = System.currentTimeMillis() - startedAt;
            return RagLlmCompletionResult.failure(
                    RagLlmProvider.CLOUD,
                    cloudModel,
                    durationMs,
                    exception.getMessage());
        }
    }

    private RagLlmCompletionResult completeLocal(List<OpenRouterHttpClient.ChatMessage> messages) {
        var status = localLlmService.checkStatus();
        if (!status.online() || !status.modelAvailable()) {
            return RagLlmCompletionResult.failure(
                    RagLlmProvider.LOCAL,
                    localModel,
                    0,
                    status.message());
        }

        List<OllamaHttpClient.ChatMessage> ollamaMessages = messages.stream()
                .map(message -> new OllamaHttpClient.ChatMessage(message.role(), message.content()))
                .toList();

        long startedAt = System.currentTimeMillis();
        try {
            OllamaHttpClient.ChatResult result = ollamaHttpClient.chatMessages(
                    ollamaMessages,
                    localModel,
                    localTemperature,
                    localMaxTokens);
            long durationMs = System.currentTimeMillis() - startedAt;
            String content = result.content() != null ? result.content().trim() : "";
            if (content.isBlank()) {
                return RagLlmCompletionResult.failure(
                        RagLlmProvider.LOCAL,
                        localModel,
                        durationMs,
                        "Локальная модель вернула пустой ответ.");
            }
            return RagLlmCompletionResult.success(
                    content,
                    RagLlmProvider.LOCAL,
                    localModel,
                    durationMs,
                    result.evalCount());
        } catch (Exception exception) {
            long durationMs = System.currentTimeMillis() - startedAt;
            return RagLlmCompletionResult.failure(
                    RagLlmProvider.LOCAL,
                    localModel,
                    durationMs,
                    exception.getMessage());
        }
    }
}

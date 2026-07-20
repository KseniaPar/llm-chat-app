package com.example.llmchat.devassist;

import com.example.llmchat.localllm.LocalLlmService;
import com.example.llmchat.rag.RagCompletionService;
import com.example.llmchat.rag.RagLlmProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * DevAssist (Day 31) uses OpenRouter ChatClient for tool-calling.
 * {@code app.devassist.llm-provider} is kept for status labels; generation requires a cloud API key.
 */
@Component
public class DevAssistLlmConfig {

    private static final String PLACEHOLDER_KEY = "local-llm-not-used";

    private final RagLlmProvider provider;
    private final String openRouterApiKey;

    public DevAssistLlmConfig(
            @Value("${app.devassist.llm-provider:cloud}") String providerName,
            @Value("${app.openrouter.api-key:}") String openRouterApiKey) {
        this.provider = "local".equalsIgnoreCase(providerName) ? RagLlmProvider.LOCAL : RagLlmProvider.CLOUD;
        this.openRouterApiKey = openRouterApiKey == null ? "" : openRouterApiKey.trim();
    }

    public RagLlmProvider provider() {
        return provider;
    }

    public String providerLabel() {
        return "cloud";
    }

    public boolean cloudConfigured() {
        return !openRouterApiKey.isBlank() && !PLACEHOLDER_KEY.equals(openRouterApiKey);
    }

    public boolean isReady(LocalLlmService localLlmService) {
        return cloudConfigured();
    }

    public String modelName(RagCompletionService completionService, LocalLlmService localLlmService) {
        return completionService.cloudModel();
    }

    public String notReadyMessage() {
        return "OPENROUTER_API_KEY не задан — tool-calling ассистент разработчика недоступен.";
    }
}

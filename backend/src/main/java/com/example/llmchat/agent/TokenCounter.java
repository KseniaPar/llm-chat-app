package com.example.llmchat.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TokenCounter {

    private static final int MESSAGE_OVERHEAD_TOKENS = 4;

    private final int contextWindow;
    private final double promptCostPerMillion;
    private final double completionCostPerMillion;

    public TokenCounter(
            @Value("${app.agent.context-window}") int contextWindow,
            @Value("${app.agent.pricing.prompt-per-million}") double promptCostPerMillion,
            @Value("${app.agent.pricing.completion-per-million}") double completionCostPerMillion) {
        this.contextWindow = contextWindow;
        this.promptCostPerMillion = promptCostPerMillion;
        this.completionCostPerMillion = completionCostPerMillion;
    }

    public int estimateTextTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(text.length() / 3.5));
    }

    public int estimateMessageTokens(String role, String content) {
        return MESSAGE_OVERHEAD_TOKENS + estimateTextTokens(role) + estimateTextTokens(content);
    }

    public int estimateMessagesTokens(List<OpenRouterHttpClient.ChatMessage> messages) {
        int total = 0;
        for (OpenRouterHttpClient.ChatMessage message : messages) {
            total += estimateMessageTokens(message.role(), message.content());
        }
        return total + 2;
    }

    public int estimateHistoryTokens(List<OpenRouterHttpClient.ChatMessage> historyMessages) {
        return estimateMessagesTokens(historyMessages);
    }

    public double calculateCostUsd(int promptTokens, int completionTokens) {
        return (promptTokens * promptCostPerMillion / 1_000_000.0)
                + (completionTokens * completionCostPerMillion / 1_000_000.0);
    }

    public int contextWindow() {
        return contextWindow;
    }

    public boolean exceedsContextWindow(int estimatedRequestTokens) {
        return estimatedRequestTokens > contextWindow;
    }
}

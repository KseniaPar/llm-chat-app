package com.example.llmchat.agent;

import com.example.llmchat.dto.AgentChatMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ContextCompressionService {

    private static final String SUMMARY_PREFIX = "Краткое содержание диалога:\n";

    private final OpenRouterHttpClient openRouterHttpClient;
    private final ConversationStore conversationStore;
    private final TokenCounter tokenCounter;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final int keepLastMessages;
    private final int summarizeEveryMessages;
    private final String summarySystemPrompt;

    public ContextCompressionService(
            OpenRouterHttpClient openRouterHttpClient,
            ConversationStore conversationStore,
            TokenCounter tokenCounter,
            @Value("${app.openrouter.model}") String model,
            @Value("${app.agent.temperature}") double temperature,
            @Value("${app.agent.max-tokens}") int maxTokens,
            @Value("${app.agent.compression.keep-last-messages}") int keepLastMessages,
            @Value("${app.agent.compression.summarize-every-messages}") int summarizeEveryMessages,
            @Value("${app.agent.compression.summary-system-prompt}") String summarySystemPrompt) {
        this.openRouterHttpClient = openRouterHttpClient;
        this.conversationStore = conversationStore;
        this.tokenCounter = tokenCounter;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.keepLastMessages = keepLastMessages;
        this.summarizeEveryMessages = summarizeEveryMessages;
        this.summarySystemPrompt = summarySystemPrompt;
    }

    public boolean shouldCompress(String sessionId, boolean compressionEnabled) {
        if (!compressionEnabled) {
            return false;
        }
        int totalCount = conversationStore.getTotalMessageCount(sessionId);
        return totalCount > 0 && totalCount % summarizeEveryMessages == 0;
    }

    public CompressionResult compressIfNeeded(String sessionId, boolean compressionEnabled) {
        if (!shouldCompress(sessionId, compressionEnabled)) {
            return CompressionResult.notApplied();
        }

        List<AgentChatMessage> messages = new ArrayList<>(conversationStore.getMessages(sessionId));
        if (messages.size() <= keepLastMessages) {
            return CompressionResult.notApplied();
        }

        List<AgentChatMessage> toSummarize = List.copyOf(messages.subList(0, messages.size() - keepLastMessages));
        List<AgentChatMessage> toKeep = List.copyOf(messages.subList(messages.size() - keepLastMessages, messages.size()));
        String existingSummary = conversationStore.getSummary(sessionId);

        String newSummary = summarize(existingSummary, toSummarize);
        conversationStore.applyCompression(sessionId, newSummary, toKeep);

        int summaryTokens = tokenCounter.estimateTextTokens(newSummary);
        return new CompressionResult(
                true,
                toSummarize.size(),
                summaryTokens,
                previewSummary(newSummary));
    }

    public String summarize(String existingSummary, List<AgentChatMessage> messages) {
        StringBuilder userContent = new StringBuilder();
        if (existingSummary != null && !existingSummary.isBlank()) {
            userContent.append("Предыдущее резюме:\n").append(existingSummary).append("\n\n");
        }
        userContent.append("Сообщения для сжатия:\n");
        for (AgentChatMessage message : messages) {
            userContent.append(message.role()).append(": ").append(message.content()).append("\n");
        }

        List<OpenRouterHttpClient.ChatMessage> request = List.of(
                new OpenRouterHttpClient.ChatMessage("system", summarySystemPrompt),
                new OpenRouterHttpClient.ChatMessage("user", userContent.toString()));

        CompletionResult completion = openRouterHttpClient.complete(model, temperature, maxTokens, request, false);
        return completion.content().trim();
    }

    public String formatSummaryForContext(String summary) {
        if (summary == null || summary.isBlank()) {
            return null;
        }
        return SUMMARY_PREFIX + summary;
    }

    public int estimateSummaryTokens(String summary) {
        String formatted = formatSummaryForContext(summary);
        if (formatted == null) {
            return 0;
        }
        return tokenCounter.estimateMessageTokens("system", formatted);
    }

    public int keepLastMessages() {
        return keepLastMessages;
    }

    public int summarizeEveryMessages() {
        return summarizeEveryMessages;
    }

    private String previewSummary(String summary) {
        if (summary == null) {
            return null;
        }
        return summary.length() > 200 ? summary.substring(0, 200) + "…" : summary;
    }

    public record CompressionResult(
            boolean applied,
            int messagesSummarized,
            int summaryTokens,
            String summaryPreview) {

        public static CompressionResult notApplied() {
            return new CompressionResult(false, 0, 0, null);
        }
    }
}

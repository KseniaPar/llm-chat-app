package com.example.llmchat.agent;

import com.example.llmchat.config.AgentChatClientConfig;
import com.example.llmchat.config.LlmProviderConfig;
import com.example.llmchat.dto.McpToolCallLogDto;
import com.example.llmchat.localllm.LocalLlmService;
import com.example.llmchat.localllm.OllamaHttpClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentChatCompletionService {

    private final ChatClient agentChatClient;
    private final OllamaHttpClient ollamaHttpClient;
    private final LocalLlmService localLlmService;
    private final LlmProviderConfig llmProviderConfig;
    private final String model;
    private final double localTemperature;
    private final int localMaxTokens;

    public AgentChatCompletionService(
            @Qualifier("agentChatClient") ChatClient agentChatClient,
            OllamaHttpClient ollamaHttpClient,
            LocalLlmService localLlmService,
            LlmProviderConfig llmProviderConfig,
            @org.springframework.beans.factory.annotation.Value("${app.openrouter.model}") String model,
            @org.springframework.beans.factory.annotation.Value("${app.local-llm.temperature}") double localTemperature,
            @org.springframework.beans.factory.annotation.Value("${app.local-llm.max-tokens}") int localMaxTokens) {
        this.agentChatClient = agentChatClient;
        this.ollamaHttpClient = ollamaHttpClient;
        this.localLlmService = localLlmService;
        this.llmProviderConfig = llmProviderConfig;
        this.model = model;
        this.localTemperature = localTemperature;
        this.localMaxTokens = localMaxTokens;
    }

    public AgentChatResult complete(
            List<OpenRouterHttpClient.ChatMessage> messages,
            double temperature,
            int maxTokens) {
        if (llmProviderConfig.isLocal()) {
            return completeLocal(messages, temperature, maxTokens);
        }

        AgentChatClientConfig.beginToolCallRecording();
        List<Message> springMessages = toSpringMessages(messages);

        ChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();

        ChatResponse chatResponse = agentChatClient.prompt()
                .messages(springMessages)
                .options(options)
                .call()
                .chatResponse();

        String content = chatResponse != null && chatResponse.getResult() != null
                ? chatResponse.getResult().getOutput().getText()
                : "";

        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;
        if (chatResponse != null && chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
            var usage = chatResponse.getMetadata().getUsage();
            promptTokens = toInt(usage.getPromptTokens());
            completionTokens = toInt(usage.getCompletionTokens());
            totalTokens = toInt(usage.getTotalTokens());
        }

        List<McpToolCallLogDto> toolCalls = AgentChatClientConfig.drainToolCallRecording();
        if (toolCalls.isEmpty()) {
            toolCalls = extractToolCallsFromResponse(chatResponse);
        }

        return new AgentChatResult(
                content != null ? content : "",
                promptTokens,
                completionTokens,
                totalTokens,
                toolCalls);
    }

    private AgentChatResult completeLocal(
            List<OpenRouterHttpClient.ChatMessage> messages,
            double temperature,
            int maxTokens) {
        List<OllamaHttpClient.ChatMessage> ollamaMessages = messages.stream()
                .map(message -> new OllamaHttpClient.ChatMessage(message.role(), message.content()))
                .toList();
        OllamaHttpClient.ChatResult result = ollamaHttpClient.chatMessages(
                ollamaMessages,
                localLlmService.model(),
                temperature > 0 ? temperature : localTemperature,
                maxTokens > 0 ? maxTokens : localMaxTokens);
        int completionTokens = (int) Math.max(0, result.evalCount());
        return new AgentChatResult(
                result.content() != null ? result.content() : "",
                0,
                completionTokens,
                completionTokens,
                List.of());
    }

    private int toInt(Number value) {
        return value != null ? value.intValue() : 0;
    }

    private List<Message> toSpringMessages(List<OpenRouterHttpClient.ChatMessage> messages) {
        List<Message> result = new ArrayList<>();
        for (OpenRouterHttpClient.ChatMessage message : messages) {
            if (message == null || message.content() == null) {
                continue;
            }
            result.add(switch (message.role()) {
                case "system" -> new SystemMessage(message.content());
                case "assistant" -> new AssistantMessage(message.content());
                default -> new UserMessage(message.content());
            });
        }
        return result;
    }

    private List<McpToolCallLogDto> extractToolCallsFromResponse(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResults() == null) {
            return List.of();
        }
        List<McpToolCallLogDto> toolCalls = new ArrayList<>();
        for (var generation : chatResponse.getResults()) {
            if (generation.getOutput() == null || generation.getOutput().getToolCalls() == null) {
                continue;
            }
            for (var toolCall : generation.getOutput().getToolCalls()) {
                toolCalls.add(new McpToolCallLogDto(
                        AgentChatClientConfig.STUDY_SERVER_NAME,
                        toolCall.name(),
                        toolCall.arguments(),
                        "(requested by model)",
                        0L));
            }
        }
        return List.copyOf(toolCalls);
    }

    public record AgentChatResult(
            String content,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            List<McpToolCallLogDto> mcpToolCalls) {
    }
}

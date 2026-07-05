package com.example.llmchat.rag;

import com.example.llmchat.agent.CompletionResult;
import com.example.llmchat.agent.OpenRouterHttpClient;
import com.example.llmchat.dto.RagQueryResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RagQueryService {

    private static final String RAG_SYSTEM_PROMPT = """
            Ты учебный ассистент по материалу «Основы православия».
            Отвечай ТОЛЬКО на основе предоставленного контекста из документа.
            Если в контексте нет ответа — честно скажи об этом.
            Отвечай кратко и по делу на русском языке.""";

    private final RagRetrievalService retrievalService;
    private final OpenRouterHttpClient openRouterHttpClient;
    private final String model;
    private final double temperature;
    private final int maxTokens;

    public RagQueryService(
            RagRetrievalService retrievalService,
            OpenRouterHttpClient openRouterHttpClient,
            @Value("${app.openrouter.model}") String model,
            @Value("${app.agent.temperature}") double temperature,
            @Value("${app.agent.max-tokens}") int maxTokens) {
        this.retrievalService = retrievalService;
        this.openRouterHttpClient = openRouterHttpClient;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    public RagQueryResponse query(String question, boolean useRag, ChunkingStrategy strategy, Integer topK) {
        if (!useRag) {
            String answer = completeWithoutRag(question);
            return new RagQueryResponse(answer, List.of(), "WITHOUT_RAG");
        }

        List<RagRetrievalService.ScoredChunk> chunks = retrievalService.search(question, strategy, topK);
        String contextBlock = buildContextBlock(chunks);
        String userMessage = contextBlock + "\n\nВопрос: " + question;

        CompletionResult completion = openRouterHttpClient.complete(
                model,
                temperature,
                maxTokens,
                List.of(
                        new OpenRouterHttpClient.ChatMessage("system", RAG_SYSTEM_PROMPT),
                        new OpenRouterHttpClient.ChatMessage("user", userMessage)),
                false);

        List<RagQueryResponse.ChunkUsedDto> used = chunks.stream()
                .map(c -> new RagQueryResponse.ChunkUsedDto(
                        c.chunkId(),
                        c.source(),
                        c.section(),
                        c.score(),
                        c.content().substring(0, Math.min(180, c.content().length()))))
                .toList();

        return new RagQueryResponse(completion.content(), used, "WITH_RAG");
    }

    private String completeWithoutRag(String question) {
        CompletionResult result = openRouterHttpClient.complete(
                model,
                temperature,
                maxTokens,
                List.of(
                        new OpenRouterHttpClient.ChatMessage(
                                "system",
                                "Ты учебный ассистент. Ответь кратко без доступа к документу."),
                        new OpenRouterHttpClient.ChatMessage("user", question)),
                false);
        return result.content();
    }

    private String buildContextBlock(List<RagRetrievalService.ScoredChunk> chunks) {
        StringBuilder builder = new StringBuilder("Контекст из «Основы православия»:\n\n");
        for (int i = 0; i < chunks.size(); i++) {
            RagRetrievalService.ScoredChunk chunk = chunks.get(i);
            builder.append("--- Фрагмент ").append(i + 1)
                    .append(" [").append(chunk.section()).append(", score=")
                    .append(String.format("%.3f", chunk.score())).append("] ---\n");
            builder.append(chunk.content()).append("\n\n");
        }
        return builder.toString();
    }
}

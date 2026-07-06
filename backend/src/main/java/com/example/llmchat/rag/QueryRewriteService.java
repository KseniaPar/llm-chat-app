package com.example.llmchat.rag;

import com.example.llmchat.agent.CompletionResult;
import com.example.llmchat.agent.OpenRouterHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class QueryRewriteService {

    private static final String REWRITE_SYSTEM_PROMPT = """
            Ты переформулируешь вопрос студента для поиска по учебному документу «Основы православия».
            Верни одну короткую поисковую фразу на русском — только ключевые термины и суть вопроса.
            Без пояснений, без кавычек, без нумерации.""";

    private final OpenRouterHttpClient openRouterHttpClient;
    private final String model;
    private final double temperature;
    private final int maxTokens;

    public QueryRewriteService(
            OpenRouterHttpClient openRouterHttpClient,
            @Value("${app.openrouter.model}") String model,
            @Value("${app.agent.temperature}") double temperature,
            @Value("${app.agent.max-tokens}") int maxTokens) {
        this.openRouterHttpClient = openRouterHttpClient;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    public String rewrite(String question) {
        CompletionResult result = openRouterHttpClient.complete(
                model,
                temperature,
                maxTokens,
                List.of(
                        new OpenRouterHttpClient.ChatMessage("system", REWRITE_SYSTEM_PROMPT),
                        new OpenRouterHttpClient.ChatMessage("user", question)),
                false);
        String rewritten = result.content().trim();
        return rewritten.isBlank() ? question : rewritten;
    }
}

package com.example.llmchat.agent;

import com.example.llmchat.config.LlmProviderConfig;
import com.example.llmchat.localllm.LocalLlmService;
import com.example.llmchat.localllm.OllamaHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OpenRouterHttpClient {

    public record ChatMessage(String role, String content) {
    }

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final HttpExchangeLogger httpExchangeLogger;
    private final OllamaHttpClient ollamaHttpClient;
    private final LocalLlmService localLlmService;
    private final LlmProviderConfig llmProviderConfig;
    private final String apiKey;
    private final String chatCompletionsUrl;
    private final double localTemperature;
    private final int localMaxTokens;

    public OpenRouterHttpClient(
            ObjectMapper objectMapper,
            HttpExchangeLogger httpExchangeLogger,
            OllamaHttpClient ollamaHttpClient,
            LocalLlmService localLlmService,
            LlmProviderConfig llmProviderConfig,
            @Value("${app.openrouter.api-key}") String apiKey,
            @Value("${app.openrouter.base-url}") String baseUrl,
            @Value("${app.local-llm.temperature}") double localTemperature,
            @Value("${app.local-llm.max-tokens}") int localMaxTokens) {
        this.objectMapper = objectMapper;
        this.httpExchangeLogger = httpExchangeLogger;
        this.ollamaHttpClient = ollamaHttpClient;
        this.localLlmService = localLlmService;
        this.llmProviderConfig = llmProviderConfig;
        this.apiKey = apiKey;
        this.chatCompletionsUrl = baseUrl + "/v1/chat/completions";
        this.localTemperature = localTemperature;
        this.localMaxTokens = localMaxTokens;
        this.restTemplate = createRestTemplate();
    }

    public CompletionResult complete(String model, double temperature, int maxTokens, List<ChatMessage> messages) {
        return complete(model, temperature, maxTokens, messages, true);
    }

    public CompletionResult complete(
            String model,
            double temperature,
            int maxTokens,
            List<ChatMessage> messages,
            boolean logExchange) {
        if (llmProviderConfig.isLocal()) {
            return completeLocal(temperature, maxTokens, messages, logExchange);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages.stream()
                .map(message -> Map.of("role", message.role(), "content", message.content()))
                .toList());
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        if (logExchange) {
            httpExchangeLogger.logRequest(HttpMethod.POST.name(), chatCompletionsUrl, headers, body);
        }

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    chatCompletionsUrl,
                    HttpMethod.POST,
                    request,
                    String.class);

            if (logExchange) {
                httpExchangeLogger.logResponse(
                        response.getStatusCode().value(),
                        response.getHeaders(),
                        response.getBody());
            }

            return parseCompletion(response.getBody());
        } catch (HttpStatusCodeException exception) {
            if (logExchange) {
                httpExchangeLogger.logResponse(
                        exception.getStatusCode().value(),
                        exception.getResponseHeaders(),
                        exception.getResponseBodyAsString(StandardCharsets.UTF_8));
            }
            throw new OpenRouterHttpException(
                    exception.getStatusCode().value(),
                    exception.getResponseBodyAsString(StandardCharsets.UTF_8));
        } catch (RestClientException exception) {
            throw new OpenRouterHttpException(502, "Сетевая ошибка OpenRouter: " + exception.getMessage());
        }
    }

    private CompletionResult completeLocal(
            double temperature,
            int maxTokens,
            List<ChatMessage> messages,
            boolean logExchange) {
        if (messages == null || messages.isEmpty()) {
            throw new OpenRouterHttpException(400, "messages must not be empty");
        }
        String model = localLlmService.model();
        List<OllamaHttpClient.ChatMessage> ollamaMessages = messages.stream()
                .map(message -> new OllamaHttpClient.ChatMessage(message.role(), message.content()))
                .toList();

        if (logExchange) {
            httpExchangeLogger.logRequest("POST", "ollama:/api/chat", null, Map.of(
                    "model", model,
                    "messages", ollamaMessages.size(),
                    "temperature", temperature,
                    "max_tokens", maxTokens));
        }

        try {
            OllamaHttpClient.ChatResult result = ollamaHttpClient.chatMessages(
                    ollamaMessages,
                    model,
                    temperature > 0 ? temperature : localTemperature,
                    maxTokens > 0 ? maxTokens : localMaxTokens);
            int completionTokens = (int) Math.max(0, result.evalCount());
            if (logExchange) {
                httpExchangeLogger.logResponse(200, null, result.content());
            }
            return new CompletionResult(
                    result.content() != null ? result.content() : "",
                    0,
                    completionTokens,
                    completionTokens);
        } catch (Exception exception) {
            throw new OpenRouterHttpException(502, "Ollama недоступен: " + exception.getMessage());
        }
    }

    private CompletionResult parseCompletion(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new OpenRouterHttpException(502, "Пустой ответ от OpenRouter.");
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new OpenRouterHttpException(502, "OpenRouter вернул ответ без choices[0].message.content.");
            }

            JsonNode usage = root.path("usage");
            int promptTokens = usage.path("prompt_tokens").asInt(0);
            int completionTokens = usage.path("completion_tokens").asInt(0);
            int totalTokens = usage.path("total_tokens").asInt(promptTokens + completionTokens);

            return new CompletionResult(content.asText(), promptTokens, completionTokens, totalTokens);
        } catch (OpenRouterHttpException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new OpenRouterHttpException(502, "Не удалось разобрать ответ OpenRouter: " + exception.getMessage());
        }
    }

    private RestTemplate createRestTemplate() {
        RestTemplate template = new RestTemplate();
        for (var converter : template.getMessageConverters()) {
            if (converter instanceof StringHttpMessageConverter stringConverter) {
                stringConverter.setDefaultCharset(StandardCharsets.UTF_8);
            }
        }
        return template;
    }
}

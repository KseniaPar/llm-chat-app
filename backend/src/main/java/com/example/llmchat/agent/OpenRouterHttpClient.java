package com.example.llmchat.agent;

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
    private final String apiKey;
    private final String chatCompletionsUrl;

    public OpenRouterHttpClient(
            ObjectMapper objectMapper,
            HttpExchangeLogger httpExchangeLogger,
            @Value("${app.openrouter.api-key}") String apiKey,
            @Value("${app.openrouter.base-url}") String baseUrl) {
        this.objectMapper = objectMapper;
        this.httpExchangeLogger = httpExchangeLogger;
        this.apiKey = apiKey;
        this.chatCompletionsUrl = baseUrl + "/v1/chat/completions";
        this.restTemplate = createRestTemplate();
    }

    public String complete(String model, double temperature, int maxTokens, List<ChatMessage> messages) {
        return complete(model, temperature, maxTokens, messages, true);
    }

    public String complete(
            String model,
            double temperature,
            int maxTokens,
            List<ChatMessage> messages,
            boolean logExchange) {
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

            return extractContent(response.getBody());
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
        }
    }

    private String extractContent(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new OpenRouterHttpException(502, "Пустой ответ от OpenRouter.");
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new OpenRouterHttpException(502, "OpenRouter вернул ответ без choices[0].message.content.");
            }
            return content.asText();
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

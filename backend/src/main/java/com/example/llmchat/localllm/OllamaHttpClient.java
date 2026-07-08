package com.example.llmchat.localllm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OllamaHttpClient {

    public record ChatResult(String content, long evalCount, long evalDurationNs) {
    }

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String configuredModel;

    public OllamaHttpClient(
            ObjectMapper objectMapper,
            RestTemplateBuilder restTemplateBuilder,
            @Value("${app.local-llm.base-url}") String baseUrl,
            @Value("${app.local-llm.model}") String configuredModel,
            @Value("${app.local-llm.request-timeout}") Duration requestTimeout) {
        this.objectMapper = objectMapper;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.configuredModel = configuredModel;
        this.restTemplate = createRestTemplate(restTemplateBuilder, requestTimeout);
    }

    public boolean isReachable() {
        try {
            restTemplate.exchange(baseUrl + "/api/tags", HttpMethod.GET, HttpEntity.EMPTY, String.class);
            return true;
        } catch (RestClientException exception) {
            return false;
        }
    }

    public List<String> listModels() {
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl + "/api/tags",
                    HttpMethod.GET,
                    HttpEntity.EMPTY,
                    String.class);
            return parseModelNames(response.getBody());
        } catch (HttpStatusCodeException exception) {
            throw new OllamaHttpException(
                    exception.getStatusCode().value(),
                    exception.getResponseBodyAsString(StandardCharsets.UTF_8));
        } catch (RestClientException exception) {
            throw new OllamaHttpException(502, "Ollama недоступен: " + exception.getMessage());
        }
    }

    public ChatResult chat(String prompt, String model, double temperature, int maxTokens) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("stream", false);
        body.put("options", Map.of(
                "temperature", temperature,
                "num_predict", maxTokens));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl + "/api/chat",
                    HttpMethod.POST,
                    request,
                    String.class);
            return parseChatResponse(response.getBody());
        } catch (HttpStatusCodeException exception) {
            throw new OllamaHttpException(
                    exception.getStatusCode().value(),
                    exception.getResponseBodyAsString(StandardCharsets.UTF_8));
        } catch (RestClientException exception) {
            throw new OllamaHttpException(502, "Ollama недоступен: " + exception.getMessage());
        }
    }

    public String configuredModel() {
        return configuredModel;
    }

    private ChatResult parseChatResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new OllamaHttpException(502, "Пустой ответ от Ollama.");
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode content = root.path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new OllamaHttpException(502, "Ollama вернул ответ без message.content.");
            }
            long evalCount = root.path("eval_count").asLong(0);
            long evalDurationNs = root.path("eval_duration").asLong(0);
            return new ChatResult(content.asText(), evalCount, evalDurationNs);
        } catch (OllamaHttpException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new OllamaHttpException(502, "Не удалось разобрать ответ Ollama: " + exception.getMessage());
        }
    }

    private List<String> parseModelNames(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return List.of();
        }
        try {
            JsonNode models = objectMapper.readTree(responseBody).path("models");
            if (!models.isArray()) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            for (JsonNode model : models) {
                String name = model.path("name").asText(null);
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
            return List.copyOf(names);
        } catch (Exception exception) {
            throw new OllamaHttpException(502, "Не удалось разобрать список моделей Ollama: " + exception.getMessage());
        }
    }

    private RestTemplate createRestTemplate(RestTemplateBuilder builder, Duration requestTimeout) {
        RestTemplate template = builder
                .setConnectTimeout(requestTimeout)
                .setReadTimeout(requestTimeout)
                .build();
        for (var converter : template.getMessageConverters()) {
            if (converter instanceof StringHttpMessageConverter stringConverter) {
                stringConverter.setDefaultCharset(StandardCharsets.UTF_8);
            }
        }
        return template;
    }

    private String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "http://localhost:11434";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}

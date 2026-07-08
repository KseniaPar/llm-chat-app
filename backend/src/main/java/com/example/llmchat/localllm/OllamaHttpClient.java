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
    private final RestTemplate chatRestTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String configuredModel;
    private final Object requestLock = new Object();

    public OllamaHttpClient(
            ObjectMapper objectMapper,
            RestTemplateBuilder restTemplateBuilder,
            @Value("${app.local-llm.base-url}") String baseUrl,
            @Value("${app.local-llm.model}") String configuredModel,
            @Value("${app.local-llm.request-timeout}") Duration requestTimeout,
            @Value("${app.local-llm.chat-request-timeout:600s}") Duration chatRequestTimeout,
            @Value("${app.local-llm.connect-timeout:30s}") Duration connectTimeout) {
        this.objectMapper = objectMapper;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.configuredModel = configuredModel;
        this.restTemplate = createRestTemplate(restTemplateBuilder, connectTimeout, requestTimeout);
        this.chatRestTemplate = createRestTemplate(restTemplateBuilder, connectTimeout, chatRequestTimeout);
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

    public record ChatMessage(String role, String content) {
    }


    public List<float[]> embedBatch(List<String> texts, String model) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> results = new ArrayList<>(texts.size());
        for (String text : texts) {
            results.add(embed(text, model));
        }
        return results;
    }

    public float[] embed(String text, String model) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text is required");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", text);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        synchronized (requestLock) {
            try {
                ResponseEntity<String> response = restTemplate.exchange(
                        baseUrl + "/api/embed",
                        HttpMethod.POST,
                        request,
                        String.class);
                return parseEmbedResponse(response.getBody());
            } catch (HttpStatusCodeException exception) {
                throw new OllamaHttpException(
                        exception.getStatusCode().value(),
                        exception.getResponseBodyAsString(StandardCharsets.UTF_8));
            } catch (RestClientException exception) {
                throw new OllamaHttpException(502, "Ollama embed недоступен: " + exception.getMessage());
            }
        }
    }

    private float[] parseEmbedResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new OllamaHttpException(502, "Пустой ответ от Ollama embed.");
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode embeddings = root.path("embeddings");
            if (embeddings.isArray() && !embeddings.isEmpty()) {
                JsonNode first = embeddings.get(0);
                return jsonArrayToVector(first);
            }
            JsonNode embedding = root.path("embedding");
            if (embedding.isArray() && !embedding.isEmpty()) {
                return jsonArrayToVector(embedding);
            }
            throw new OllamaHttpException(502, "Ollama embed вернул ответ без embeddings.");
        } catch (OllamaHttpException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new OllamaHttpException(502, "Не удалось разобрать Ollama embed: " + exception.getMessage());
        }
    }

    private float[] jsonArrayToVector(JsonNode array) {
        float[] vector = new float[array.size()];
        for (int i = 0; i < array.size(); i++) {
            vector[i] = (float) array.get(i).asDouble();
        }
        return vector;
    }

    public ChatResult chat(String prompt, String model, double temperature, int maxTokens) {
        return chatMessages(List.of(new ChatMessage("user", prompt)), model, temperature, maxTokens);
    }

    public ChatResult chatMessages(List<ChatMessage> messages, String model, double temperature, int maxTokens) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }

        List<Map<String, String>> ollamaMessages = messages.stream()
                .filter(message -> message != null
                        && message.content() != null
                        && !message.content().isBlank())
                .map(message -> Map.of(
                        "role", normalizeRole(message.role()),
                        "content", message.content().trim()))
                .toList();
        if (ollamaMessages.isEmpty()) {
            throw new IllegalArgumentException("messages must contain at least one non-blank message");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", ollamaMessages);
        body.put("stream", false);
        body.put("keep_alive", "30m");
        body.put("options", Map.of(
                "temperature", temperature,
                "num_predict", maxTokens));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        synchronized (requestLock) {
            try {
                ResponseEntity<String> response = chatRestTemplate.exchange(
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
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "user";
        }
        return switch (role.toLowerCase()) {
            case "system", "assistant", "user" -> role.toLowerCase();
            default -> "user";
        };
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

    private RestTemplate createRestTemplate(
            RestTemplateBuilder builder,
            Duration connectTimeout,
            Duration readTimeout) {
        RestTemplate template = builder
                .setConnectTimeout(connectTimeout)
                .setReadTimeout(readTimeout)
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

package com.example.llmchat.rag;

import com.example.llmchat.localllm.LocalLlmService;
import com.example.llmchat.localllm.OllamaHttpClient;
import com.example.llmchat.localllm.OllamaHttpException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    public enum EmbeddingSource {
        OLLAMA,
        OPENROUTER,
        KEYWORD_FALLBACK
    }

    public record EmbedResult(float[] vector, EmbeddingSource source, String note) {
        public static EmbedResult of(float[] vector, EmbeddingSource source) {
            return new EmbedResult(vector, source, null);
        }

        public static EmbedResult keywordFallback(String note) {
            return new EmbedResult(new float[0], EmbeddingSource.KEYWORD_FALLBACK, note);
        }
    }

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final OllamaHttpClient ollamaHttpClient;
    private final LocalLlmService localLlmService;
    private final String apiKey;
    private final String embeddingsUrl;
    private final String cloudModel;
    private final String localModel;
    private final int batchSize;
    private final boolean cloudFallbackOnFailure;

    public EmbeddingService(
            ObjectMapper objectMapper,
            RestTemplateBuilder restTemplateBuilder,
            OllamaHttpClient ollamaHttpClient,
            LocalLlmService localLlmService,
            @Value("${app.openrouter.api-key}") String apiKey,
            @Value("${app.openrouter.base-url}") String baseUrl,
            @Value("${app.rag.cloud.embedding-model:openai/text-embedding-3-small}") String cloudModel,
            @Value("${app.rag.local.embedding-model:nomic-embed-text}") String localModel,
            @Value("${app.rag.embedding-batch-size:64}") int batchSize,
            @Value("${app.rag.embedding-timeout:30s}") Duration embeddingTimeout,
            @Value("${app.rag.embedding-fallback-on-failure:true}") boolean cloudFallbackOnFailure) {
        this.objectMapper = objectMapper;
        this.ollamaHttpClient = ollamaHttpClient;
        this.localLlmService = localLlmService;
        this.apiKey = apiKey;
        this.embeddingsUrl = baseUrl + "/v1/embeddings";
        this.cloudModel = cloudModel;
        this.localModel = localModel;
        this.batchSize = Math.max(1, batchSize);
        this.cloudFallbackOnFailure = cloudFallbackOnFailure;
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(embeddingTimeout)
                .setReadTimeout(embeddingTimeout)
                .build();
    }

    public String localModel() {
        return localModel;
    }

    public String cloudModel() {
        return cloudModel;
    }

    public EmbedResult embedForQuery(String text, RagStack stack) {
        if (text == null || text.isBlank()) {
            return EmbedResult.keywordFallback("Пустой запрос.");
        }
        if (stack == RagStack.LOCAL || stack == RagStack.PROJECT || stack == RagStack.SUPPORT
                || stack == RagStack.EXAM) {
            return embedLocal(text);
        }
        return embedCloudQuery(text);
    }

    public List<float[]> embedBatch(List<String> texts, RagStack stack) {
        if (texts.isEmpty()) {
            return List.of();
        }
        if (stack == RagStack.LOCAL || stack == RagStack.PROJECT || stack == RagStack.SUPPORT
                || stack == RagStack.EXAM) {
            ensureLocalEmbeddingReady();
            List<float[]> all = new ArrayList<>(texts.size());
            for (int start = 0; start < texts.size(); start += batchSize) {
                int end = Math.min(start + batchSize, texts.size());
                log.info("Local embedding batch {}-{}/{}", start + 1, end, texts.size());
                for (int i = start; i < end; i++) {
                    all.add(ollamaHttpClient.embed(texts.get(i), localModel));
                }
            }
            return all;
        }
        ensureCloudApiKeyConfigured();
        List<float[]> all = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, texts.size());
            List<String> batch = texts.subList(start, end);
            log.info("Cloud embedding batch {}-{}/{}", start + 1, end, texts.size());
            all.addAll(requestOpenRouterEmbeddings(batch));
        }
        if (all.size() != texts.size()) {
            throw new IllegalStateException(
                    "Embedding count mismatch: expected " + texts.size() + ", got " + all.size());
        }
        return all;
    }

    public float[] embed(String text) {
        return embedBatch(List.of(text), RagStack.CLOUD).get(0);
    }

    private EmbedResult embedLocal(String text) {
        ensureLocalEmbeddingReady();
        try {
            return EmbedResult.of(ollamaHttpClient.embed(text, localModel), EmbeddingSource.OLLAMA);
        } catch (OllamaHttpException exception) {
            throw new IllegalStateException(
                    "Локальный embedding недоступен: " + exception.getMessage()
                            + ". Выполните: ollama pull " + localModel,
                    exception);
        }
    }

    private EmbedResult embedCloudQuery(String text) {
        try {
            ensureCloudApiKeyConfigured();
            return EmbedResult.of(requestOpenRouterEmbeddings(List.of(text)).get(0), EmbeddingSource.OPENROUTER);
        } catch (IllegalStateException exception) {
            if (!cloudFallbackOnFailure) {
                throw exception;
            }
            String note = "OpenRouter embeddings недоступен — keyword-only retrieval: "
                    + shorten(exception.getMessage());
            log.warn(note);
            return EmbedResult.keywordFallback(note);
        }
    }

    private void ensureLocalEmbeddingReady() {
        var status = localLlmService.checkStatus();
        if (!status.online()) {
            throw new IllegalStateException("Ollama недоступен: " + status.message());
        }
        boolean modelAvailable = status.installedModels().stream().anyMatch(this::matchesLocalEmbedModel);
        if (!modelAvailable) {
            throw new IllegalStateException(
                    "Модель embedding " + localModel + " не найдена в Ollama. Выполните: ollama pull " + localModel);
        }
    }

    private boolean matchesLocalEmbedModel(String installedName) {
        if (installedName == null || installedName.isBlank()) {
            return false;
        }
        return installedName.equals(localModel) || installedName.startsWith(localModel + ":");
    }

    private void ensureCloudApiKeyConfigured() {
        if (apiKey == null || apiKey.isBlank() || "local-llm-not-used".equals(apiKey)) {
            throw new IllegalStateException("OPENROUTER_API_KEY не задан — нужен для облачного RAG.");
        }
    }

    private List<float[]> requestOpenRouterEmbeddings(List<String> texts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cloudModel);
        body.put("input", texts);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    embeddingsUrl,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class);
            return parseOpenRouterEmbeddings(response.getBody(), texts.size());
        } catch (RestClientException exception) {
            throw new IllegalStateException(
                    "Embedding API failed: " + exception.getMessage()
                            + ". Проверьте доступ к openrouter.ai.",
                    exception);
        }
    }

    private List<float[]> parseOpenRouterEmbeddings(String json, int expectedCount) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode error = root.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                String message = error.path("message").asText(error.toString());
                throw new IllegalStateException("Embedding API error: " + message);
            }
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new IllegalStateException("Embedding API returned no data");
            }
            float[][] ordered = new float[expectedCount][];
            for (JsonNode item : data) {
                int index = item.path("index").asInt();
                JsonNode embedding = item.path("embedding");
                float[] vector = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vector[i] = (float) embedding.get(i).asDouble();
                }
                ordered[index] = vector;
            }
            List<float[]> result = new ArrayList<>(expectedCount);
            for (int i = 0; i < expectedCount; i++) {
                if (ordered[i] == null) {
                    throw new IllegalStateException("Embedding API missing vector at index " + i);
                }
                result.add(ordered[i]);
            }
            return result;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse embeddings response", exception);
        }
    }

    private static String shorten(String message) {
        if (message == null || message.isBlank()) {
            return "unknown error";
        }
        return message.length() > 120 ? message.substring(0, 120) + "…" : message;
    }

    public static double cosineSimilarity(float[] a, float[] b) {
        if (a.length == 0 || b.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public static byte[] serialize(float[] vector) {
        byte[] bytes = new byte[vector.length * 4];
        for (int i = 0; i < vector.length; i++) {
            int bits = Float.floatToIntBits(vector[i]);
            bytes[i * 4] = (byte) (bits >> 24);
            bytes[i * 4 + 1] = (byte) (bits >> 16);
            bytes[i * 4 + 2] = (byte) (bits >> 8);
            bytes[i * 4 + 3] = (byte) bits;
        }
        return bytes;
    }

    public static float[] deserialize(byte[] bytes) {
        float[] vector = new float[bytes.length / 4];
        for (int i = 0; i < vector.length; i++) {
            int bits = ((bytes[i * 4] & 0xFF) << 24)
                    | ((bytes[i * 4 + 1] & 0xFF) << 16)
                    | ((bytes[i * 4 + 2] & 0xFF) << 8)
                    | (bytes[i * 4 + 3] & 0xFF);
            vector[i] = Float.intBitsToFloat(bits);
        }
        return vector;
    }
}

package com.example.llmchat.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String embeddingsUrl;
    private final String model;
    private final int batchSize;

    public EmbeddingService(
            ObjectMapper objectMapper,
            @Value("${app.openrouter.api-key}") String apiKey,
            @Value("${app.openrouter.base-url}") String baseUrl,
            @Value("${app.rag.embedding-model:openai/text-embedding-3-small}") String model,
            @Value("${app.rag.embedding-batch-size:64}") int batchSize) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.embeddingsUrl = baseUrl + "/v1/embeddings";
        this.model = model;
        this.batchSize = Math.max(1, batchSize);
    }

    public float[] embed(String text) {
        List<float[]> batch = embedBatch(List.of(text));
        return batch.isEmpty() ? new float[0] : batch.get(0);
    }

    public List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        ensureApiKeyConfigured();
        List<float[]> all = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, texts.size());
            List<String> batch = texts.subList(start, end);
            log.info("Embedding batch {}-{}/{}", start + 1, end, texts.size());
            all.addAll(requestEmbeddings(batch));
        }
        if (all.size() != texts.size()) {
            throw new IllegalStateException(
                    "Embedding count mismatch: expected " + texts.size() + ", got " + all.size());
        }
        return all;
    }

    private void ensureApiKeyConfigured() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENROUTER_API_KEY не задан — нужен для индексации");
        }
    }

    private List<float[]> requestEmbeddings(List<String> texts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
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
            return parseEmbeddings(response.getBody(), texts.size());
        } catch (RestClientException exception) {
            throw new IllegalStateException("Embedding API failed: " + exception.getMessage(), exception);
        }
    }

    private List<float[]> parseEmbeddings(String json, int expectedCount) {
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
                if (!item.has("index")) {
                    throw new IllegalStateException("Embedding API response missing index field");
                }
                int index = item.path("index").asInt();
                if (index < 0 || index >= expectedCount) {
                    throw new IllegalStateException("Embedding API returned unexpected index: " + index);
                }
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

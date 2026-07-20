package com.example.llmchat.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class OpenRouterTranscriptionClient {

    private static final String FALLBACK_MODEL = "openai/whisper-1";

    public record TranscriptSegment(double startSec, double endSec, String text) {
    }

    public record TranscriptionResult(
            String text,
            String language,
            double durationSec,
            List<TranscriptSegment> segments) {
    }

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final HttpExchangeLogger httpExchangeLogger;
    private final String apiKey;
    private final String transcriptionsUrl;
    private final String defaultModel;

    public OpenRouterTranscriptionClient(
            ObjectMapper objectMapper,
            HttpExchangeLogger httpExchangeLogger,
            @Value("${app.openrouter.api-key}") String apiKey,
            @Value("${app.openrouter.base-url}") String baseUrl,
            @Value("${app.exam.transcription-model:openai/whisper-large-v3}") String defaultModel) {
        this.objectMapper = objectMapper;
        this.httpExchangeLogger = httpExchangeLogger;
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.transcriptionsUrl = baseUrl + "/v1/audio/transcriptions";
        this.defaultModel = defaultModel;
        this.restTemplate = createRestTemplate();
    }

    public boolean configured() {
        return !apiKey.isBlank() && !"local-llm-not-used".equals(apiKey);
    }

    public TranscriptionResult transcribeFile(Path audioPath, String language) {
        if (!configured()) {
            throw new IllegalStateException("OPENROUTER_API_KEY не задан — транскрипция недоступна.");
        }
        if (audioPath == null || !Files.isRegularFile(audioPath)) {
            throw new IllegalArgumentException("Аудиофайл не найден: " + audioPath);
        }
        long sizeBytes;
        try {
            sizeBytes = Files.size(audioPath);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Не удалось прочитать размер файла: " + exception.getMessage());
        }
        String model = sizeBytes > 25L * 1024 * 1024 ? FALLBACK_MODEL : defaultModel;
        RestClientException lastError = null;
        OpenRouterHttpException lastHttpError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return transcribeMultipartOnce(audioPath, language, model, attempt > 1);
            } catch (OpenRouterHttpException exception) {
                lastHttpError = exception;
                if (!isRetryableStatus(exception.statusCode()) || attempt == 3) {
                    break;
                }
                sleepBackoff(attempt);
            } catch (RestClientException exception) {
                lastError = exception;
                if (!isRetryable(exception) || attempt == 3) {
                    break;
                }
                sleepBackoff(attempt);
            }
        }
        if (FALLBACK_MODEL.equals(model)) {
            String msg = lastHttpError != null ? lastHttpError.getMessage()
                    : lastError != null ? lastError.getMessage() : "STT failed";
            throw new OpenRouterHttpException(lastHttpError != null ? lastHttpError.statusCode() : 502, msg);
        }
        try {
            return transcribeMultipartOnce(audioPath, language, FALLBACK_MODEL, true);
        } catch (RestClientException fallbackError) {
            throw new OpenRouterHttpException(502,
                    (lastError != null ? lastError.getMessage() : "STT failed")
                            + "; fallback " + FALLBACK_MODEL + ": " + fallbackError.getMessage());
        }
    }

    /** @deprecated prefer {@link #transcribeFile(Path, String)} for large files */
    public TranscriptionResult transcribe(byte[] audioBytes, String format, String language) {
        if (!configured()) {
            throw new IllegalStateException("OPENROUTER_API_KEY не задан — транскрипция недоступна.");
        }
        if (audioBytes == null || audioBytes.length == 0) {
            throw new IllegalArgumentException("Пустой аудиофайл.");
        }
        String audioFormat = normalizeFormat(format);

        Map<String, Object> inputAudio = Map.of(
                "data", java.util.Base64.getEncoder().encodeToString(audioBytes),
                "format", audioFormat);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", defaultModel);
        body.put("input_audio", inputAudio);
        body.put("language", language != null && !language.isBlank() ? language : "ru");
        body.put("response_format", "verbose_json");
        body.put("timestamp_granularities", List.of("segment"));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        httpExchangeLogger.logRequest(HttpMethod.POST.name(), transcriptionsUrl, headers,
                Map.of("multipart", false, "bytes", audioBytes.length, "model", defaultModel));
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    transcriptionsUrl, HttpMethod.POST, request, String.class);
            httpExchangeLogger.logResponse(
                    response.getStatusCode().value(), response.getHeaders(), response.getBody());
            return parseResponse(response.getBody());
        } catch (HttpStatusCodeException exception) {
            String errorBody = exception.getResponseBodyAsString(StandardCharsets.UTF_8);
            httpExchangeLogger.logResponse(
                    exception.getStatusCode().value(), exception.getResponseHeaders(), errorBody);
            throw new OpenRouterHttpException(exception.getStatusCode().value(), errorBody);
        } catch (RestClientException exception) {
            throw new OpenRouterHttpException(0, exception.getMessage());
        }
    }

    private TranscriptionResult transcribeMultipartOnce(
            Path audioPath, String language, String model, boolean plainJsonOnly) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(audioPath.toFile()));
        body.add("model", model);
        body.add("language", language != null && !language.isBlank() ? language : "ru");
        body.add("response_format", plainJsonOnly ? "json" : "verbose_json");
        if (!plainJsonOnly) {
            body.add("timestamp_granularities", "segment");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(apiKey);
        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        long sizeBytes;
        try {
            sizeBytes = Files.size(audioPath);
        } catch (Exception exception) {
            sizeBytes = -1;
        }
        httpExchangeLogger.logRequest(HttpMethod.POST.name(), transcriptionsUrl, headers,
                Map.of("multipart", true, "file", audioPath.getFileName().toString(),
                        "bytes", sizeBytes, "model", model, "response_format", plainJsonOnly ? "json" : "verbose_json"));

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    transcriptionsUrl, HttpMethod.POST, request, String.class);
            httpExchangeLogger.logResponse(
                    response.getStatusCode().value(), response.getHeaders(), truncateForLog(response.getBody()));
            return parseResponse(response.getBody());
        } catch (HttpStatusCodeException exception) {
            String errorBody = exception.getResponseBodyAsString(StandardCharsets.UTF_8);
            httpExchangeLogger.logResponse(
                    exception.getStatusCode().value(), exception.getResponseHeaders(), errorBody);
            if (!plainJsonOnly && shouldFallbackToPlainJson(exception.getStatusCode().value(), errorBody)) {
                return transcribeMultipartOnce(audioPath, language, model, true);
            }
            throw new OpenRouterHttpException(exception.getStatusCode().value(), errorBody);
        }
    }

    private static boolean shouldFallbackToPlainJson(int status, String errorBody) {
        if (status >= 500) {
            return false;
        }
        return status == 400 && errorBody != null && errorBody.contains("verbose_json");
    }

    private static boolean isRetryableStatus(int status) {
        return status == 502 || status == 503 || status == 504 || status == 429;
    }

    private static boolean isRetryable(RestClientException exception) {
        String message = exception.getMessage() != null ? exception.getMessage().toLowerCase(Locale.ROOT) : "";
        return message.contains("502") || message.contains("503") || message.contains("504")
                || message.contains("timeout") || message.contains("gateway");
    }

    private static void sleepBackoff(int attempt) {
        try {
            Thread.sleep(2000L * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private TranscriptionResult parseResponse(String body) throws RestClientException {
        try {
            JsonNode root = objectMapper.readTree(body);
            String text = root.path("text").asText("");
            String language = root.path("language").asText("ru");
            double duration = root.path("duration").asDouble(0);
            List<TranscriptSegment> segments = new ArrayList<>();
            JsonNode segmentsNode = root.path("segments");
            if (segmentsNode.isArray()) {
                for (JsonNode segment : segmentsNode) {
                    double start = segment.path("start").asDouble(0);
                    double end = segment.path("end").asDouble(start);
                    String segmentText = segment.path("text").asText("").trim();
                    if (!segmentText.isBlank()) {
                        segments.add(new TranscriptSegment(start, end, segmentText));
                    }
                }
            }
            if (segments.isEmpty() && !text.isBlank()) {
                segments = splitPlainText(text);
            }
            return new TranscriptionResult(text.trim(), language, duration, segments);
        } catch (Exception exception) {
            throw new RestClientException("Failed to parse transcription: " + exception.getMessage(), exception);
        }
    }

    private static List<TranscriptSegment> splitPlainText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] parts = text.split("(?<=[.!?…])\\s+");
        List<TranscriptSegment> segments = new ArrayList<>();
        double cursor = 0;
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            double estDuration = Math.max(3, trimmed.length() / 12.0);
            segments.add(new TranscriptSegment(cursor, cursor + estDuration, trimmed));
            cursor += estDuration;
        }
        return segments;
    }

    private static String normalizeFormat(String format) {
        if (format == null || format.isBlank()) {
            return "mp3";
        }
        String normalized = format.toLowerCase(Locale.ROOT).replace(".", "");
        return switch (normalized) {
            case "mpeg" -> "mp3";
            case "x-m4a" -> "m4a";
            default -> normalized;
        };
    }

    private static String truncateForLog(String body) {
        if (body == null) {
            return null;
        }
        return body.length() > 500 ? body.substring(0, 500) + "…" : body;
    }

    private static RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(60).toMillis());
        factory.setReadTimeout((int) Duration.ofMinutes(20).toMillis());
        RestTemplate restTemplate = new RestTemplate(factory);
        restTemplate.getMessageConverters().removeIf(
                converter -> converter instanceof StringHttpMessageConverter);
        restTemplate.getMessageConverters().add(0, new StringHttpMessageConverter(StandardCharsets.UTF_8));
        return restTemplate;
    }
}

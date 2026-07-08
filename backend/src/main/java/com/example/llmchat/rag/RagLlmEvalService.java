package com.example.llmchat.rag;

import com.example.llmchat.dto.RagEvalQuestionDto;
import com.example.llmchat.dto.RagLlmEvalResponse;
import com.example.llmchat.dto.RagLlmEvalResultDto;
import com.example.llmchat.dto.RagLlmCompareResponse;
import com.example.llmchat.dto.RagQueryResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class RagLlmEvalService {

    private final RagEvalQuestionLoader questionLoader;
    private final RagQueryService queryService;

    public RagLlmEvalService(RagEvalQuestionLoader questionLoader, RagQueryService queryService) {
        this.questionLoader = questionLoader;
        this.queryService = queryService;
    }

    public RagLlmEvalResponse runLlmCompareEval(ChunkingStrategy strategy, Integer topK, Double minSimilarity) {
        List<RagEvalQuestionDto> questions = questionLoader.loadAll();
        List<RagLlmEvalResultDto> results = new ArrayList<>();

        List<Long> localDurations = new ArrayList<>();
        List<Long> cloudDurations = new ArrayList<>();
        int localSuccess = 0;
        int cloudSuccess = 0;
        int localErrors = 0;
        int cloudErrors = 0;
        int localSourceMatches = 0;
        int cloudSourceMatches = 0;

        for (RagEvalQuestionDto question : questions) {
            RagLlmCompareResponse compared = queryService.compareLlmProviders(
                    question.question(), strategy, topK, minSimilarity);

            List<String> localMatched = matchSources(question, compared.localResponse());
            List<String> cloudMatched = matchSources(question, compared.cloudResponse());

            localSourceMatches += localMatched.size();
            cloudSourceMatches += cloudMatched.size();

            if (Boolean.TRUE.equals(compared.localResponse().generationSuccess())) {
                localSuccess++;
            } else {
                localErrors++;
            }
            if (Boolean.TRUE.equals(compared.cloudResponse().generationSuccess())) {
                cloudSuccess++;
            } else {
                cloudErrors++;
            }

            if (compared.localResponse().generationDurationMs() != null) {
                localDurations.add(compared.localResponse().generationDurationMs());
            }
            if (compared.cloudResponse().generationDurationMs() != null) {
                cloudDurations.add(compared.cloudResponse().generationDurationMs());
            }

            results.add(new RagLlmEvalResultDto(
                    question,
                    compared.localResponse(),
                    compared.cloudResponse(),
                    localMatched,
                    cloudMatched,
                    compared.summary().retrievalMs()));
        }

        return new RagLlmEvalResponse(
                questions.size(),
                buildSummary(
                        RagLlmProvider.LOCAL,
                        queryService.localModelName(),
                        localDurations,
                        localSuccess,
                        localErrors,
                        localSourceMatches,
                        questions.size()),
                buildSummary(
                        RagLlmProvider.CLOUD,
                        queryService.cloudModelName(),
                        cloudDurations,
                        cloudSuccess,
                        cloudErrors,
                        cloudSourceMatches,
                        questions.size()),
                results);
    }

    private RagLlmEvalResponse.RagLlmEvalSummaryDto buildSummary(
            RagLlmProvider provider,
            String model,
            List<Long> durations,
            int successCount,
            int errorCount,
            int totalSourceMatches,
            int questionCount) {
        long avgMs = average(durations);
        long maxMs = durations.stream().max(Long::compareTo).orElse(0L);
        double avgMatches = questionCount == 0 ? 0.0 : (double) totalSourceMatches / questionCount;

        String quality = assessQuality(provider, avgMatches, successCount, questionCount);
        String stability = assessStability(successCount, errorCount, questionCount);

        return new RagLlmEvalResponse.RagLlmEvalSummaryDto(
                new RagLlmEvalResponse.RagLlmProviderInfoDto(provider.name(), model),
                avgMs,
                maxMs,
                successCount,
                errorCount,
                totalSourceMatches,
                avgMatches,
                quality,
                stability);
    }

    private static long average(List<Long> values) {
        if (values.isEmpty()) {
            return 0;
        }
        return Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0));
    }

    private static String assessQuality(
            RagLlmProvider provider,
            double avgSourceMatches,
            int successCount,
            int questionCount) {
        if (successCount == 0) {
            return provider == RagLlmProvider.LOCAL
                    ? "Локальная модель не дала успешных ответов — проверьте Ollama."
                    : "Облачная модель недоступна — проверьте OPENROUTER_API_KEY.";
        }
        if (avgSourceMatches >= 2.0) {
            return "Высокое — в среднем " + String.format(Locale.ROOT, "%.1f", avgSourceMatches)
                    + " совпадений источников на вопрос.";
        }
        if (avgSourceMatches >= 1.0) {
            return "Среднее — частичное покрытие expectedSources.";
        }
        return "Низкое — мало совпадений с ожидаемыми источниками (" + successCount + "/" + questionCount + " успешных).";
    }

    private static String assessStability(int successCount, int errorCount, int questionCount) {
        double rate = questionCount == 0 ? 0.0 : (double) successCount / questionCount * 100.0;
        if (errorCount == 0) {
            return String.format(Locale.ROOT, "Стабильно — 100%% успешных ответов (%d/%d).", successCount, questionCount);
        }
        return String.format(Locale.ROOT, "%.0f%% успешных (%d/%d), ошибок: %d.", rate, successCount, questionCount, errorCount);
    }

    private List<String> matchSources(RagEvalQuestionDto question, RagQueryResponse response) {
        List<String> matched = new ArrayList<>();
        if (response == null || response.answer() == null) {
            return matched;
        }
        String answerLower = response.answer().toLowerCase(Locale.ROOT);
        for (String source : question.expectedSources()) {
            if (answerLower.contains(source.toLowerCase(Locale.ROOT))) {
                matched.add(source);
            }
        }
        for (RagQueryResponse.ChunkUsedDto chunk : response.chunksUsed()) {
            String haystack = (chunk.section() + " " + chunk.preview()).toLowerCase(Locale.ROOT);
            for (String source : question.expectedSources()) {
                if (haystack.contains(source.toLowerCase(Locale.ROOT)) && !matched.contains(source)) {
                    matched.add(source);
                }
            }
        }
        if (!response.sources().isEmpty() && matched.isEmpty()) {
            for (var src : response.sources()) {
                for (String expected : question.expectedSources()) {
                    String section = src.section() != null ? src.section().toLowerCase(Locale.ROOT) : "";
                    if (section.contains(expected.toLowerCase(Locale.ROOT)) && !matched.contains(expected)) {
                        matched.add(expected);
                    }
                }
            }
        }
        return matched;
    }
}

package com.example.llmchat.rag;

import com.example.llmchat.dto.RagLocalDemoResponse;
import com.example.llmchat.dto.RagLocalDemoRunResponse;
import com.example.llmchat.dto.RagLocalDemoScenarioDto;
import com.example.llmchat.dto.RagLocalDemoScenarioResultDto;
import com.example.llmchat.dto.RagLlmCompareResponse;
import com.example.llmchat.dto.RagQueryResponse;
import com.example.llmchat.localllm.LocalLlmService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class RagLocalDemoService {

    private static final List<RagLocalDemoScenarioDto> SCENARIOS = List.of(
            new RagLocalDemoScenarioDto(
                    1,
                    "Простой",
                    "Определение таинства",
                    "Что такое Крещение?",
                    List.of("Крещение", "таинство")),
            new RagLocalDemoScenarioDto(
                    2,
                    "Простой",
                    "Количество таинств",
                    "Сколько таинств совершает Православная Церковь?",
                    List.of("Таинства", "семь")),
            new RagLocalDemoScenarioDto(
                    3,
                    "Средний",
                    "Евхаристия",
                    "Когда и как было установлено таинство Евхаристии? Что означает слово «Евхаристия»?",
                    List.of("Евхаристия", "Тайная вечеря", "Литургия")),
            new RagLocalDemoScenarioDto(
                    4,
                    "Средний",
                    "Устройство храма",
                    "Назовите основные части православного храма и назначение алтаря.",
                    List.of("храм", "алтарь", "иконостас")),
            new RagLocalDemoScenarioDto(
                    5,
                    "Сложный",
                    "Семь таинств",
                    "Перечислите семь Таинств Православной Церкви и кратко назовите смысл каждого.",
                    List.of("Таинства", "Крещение", "Евхаристия", "Символ веры")),
            new RagLocalDemoScenarioDto(
                    6,
                    "Off-topic",
                    "Режим «не знаю»",
                    "Как приготовить борщ по рецепту из учебника?",
                    List.of()));

    private final RagIndexStore indexStore;
    private final RagCompletionService completionService;
    private final EmbeddingService embeddingService;
    private final RagQueryService queryService;
    private final LocalLlmService localLlmService;
    private final RagLocalIndexService localIndexService;

    public RagLocalDemoService(
            RagIndexStore indexStore,
            RagCompletionService completionService,
            EmbeddingService embeddingService,
            RagQueryService queryService,
            LocalLlmService localLlmService,
            RagLocalIndexService localIndexService) {
        this.indexStore = indexStore;
        this.completionService = completionService;
        this.embeddingService = embeddingService;
        this.queryService = queryService;
        this.localLlmService = localLlmService;
        this.localIndexService = localIndexService;
    }

    public RagLocalDemoResponse buildDemo() {
        var status = localLlmService.checkStatus();
        var indexStatus = localIndexService.status();

        return new RagLocalDemoResponse(
                "День 28 — Локальная LLM + RAG",
                "Полностью локальный стек: Ollama embeddings + локальный индекс + Ollama chat. "
                        + "Сравнение с облаком: OpenRouter embeddings + облачный индекс + OpenRouter chat.",
                indexStatus.localIndexPath(),
                indexStatus.localChunkCount(),
                indexStatus.cloudIndexPath(),
                indexStatus.cloudChunkCount(),
                completionService.localModel(),
                embeddingService.localModel(),
                completionService.cloudModel(),
                embeddingService.cloudModel(),
                status.message(),
                indexStatus.localIndexReady(),
                List.of(
                        "1. Локальный индекс: rag-index-local.db (Ollama " + embeddingService.localModel() + ")",
                        "2. Вопрос → Ollama embed → cosine search по локальному индексу",
                        "3. Контекст + вопрос → Ollama chat (генерация)",
                        "4. Сравнение: облачный индекс + OpenRouter chat на том же вопросе",
                        "5. Оценка: качество, скорость, стабильность"),
                List.of(
                        "Локально — без OpenRouter для основного RAG",
                        "Облако — только для сравнения (если доступен OPENROUTER_API_KEY)",
                        "Первый запуск: ollama pull nomic-embed-text"),
                SCENARIOS);
    }

    public List<RagLocalDemoScenarioDto> scenarios() {
        return SCENARIOS;
    }

    public RagLocalDemoRunResponse runAll() {
        long startedAt = System.currentTimeMillis();
        List<RagLocalDemoScenarioResultDto> results = SCENARIOS.stream()
                .map(this::runScenarioInternal)
                .toList();
        long totalDurationMs = System.currentTimeMillis() - startedAt;
        return new RagLocalDemoRunResponse(
                SCENARIOS.size(),
                totalDurationMs,
                buildSummary(results),
                results);
    }

    public RagLocalDemoScenarioResultDto runScenario(int scenarioId) {
        RagLocalDemoScenarioDto scenario = SCENARIOS.stream()
                .filter(item -> item.id() == scenarioId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown scenario id: " + scenarioId));
        return runScenarioInternal(scenario);
    }

    private RagLocalDemoScenarioResultDto runScenarioInternal(RagLocalDemoScenarioDto scenario) {
        RagLlmCompareResponse compare = queryService.compareLlmProviders(
                scenario.question(),
                ChunkingStrategy.STRUCTURE,
                null,
                null);
        return new RagLocalDemoScenarioResultDto(scenario, compare);
    }

    private RagLocalDemoRunResponse.RagLocalDemoRunSummaryDto buildSummary(
            List<RagLocalDemoScenarioResultDto> results) {
        long localMsSum = 0;
        long cloudMsSum = 0;
        long retrievalMsSum = 0;
        int localWins = 0;
        int cloudWins = 0;
        int localSuccess = 0;
        int cloudSuccess = 0;
        int localSourceHits = 0;
        int cloudSourceHits = 0;

        for (RagLocalDemoScenarioResultDto result : results) {
            RagLlmCompareResponse compare = result.compare();
            RagQueryResponse local = compare.localResponse();
            RagQueryResponse cloud = compare.cloudResponse();

            long localMs = local.generationDurationMs() != null ? local.generationDurationMs() : 0;
            long cloudMs = cloud.generationDurationMs() != null ? cloud.generationDurationMs() : 0;
            localMsSum += localMs;
            cloudMsSum += cloudMs;
            retrievalMsSum += compare.summary().retrievalMs();

            if (Boolean.TRUE.equals(local.generationSuccess())) {
                localSuccess++;
            }
            if (Boolean.TRUE.equals(cloud.generationSuccess())) {
                cloudSuccess++;
            }

            if ("LOCAL".equals(compare.summary().speedWinner())) {
                localWins++;
            } else if ("CLOUD".equals(compare.summary().speedWinner())) {
                cloudWins++;
            }

            localSourceHits += countSourceMatches(result.scenario(), local);
            cloudSourceHits += countSourceMatches(result.scenario(), cloud);
        }

        int count = results.size();
        long avgLocal = count == 0 ? 0 : Math.round((double) localMsSum / count);
        long avgCloud = count == 0 ? 0 : Math.round((double) cloudMsSum / count);
        long avgRetrieval = count == 0 ? 0 : Math.round((double) retrievalMsSum / count);

        String speedVerdict = avgLocal < avgCloud
                ? String.format(Locale.ROOT, "LOCAL быстрее в среднем (%d vs %d ms)", avgLocal, avgCloud)
                : String.format(Locale.ROOT, "CLOUD быстрее в среднем (%d vs %d ms)", avgCloud, avgLocal);

        String qualityVerdict = localSourceHits >= cloudSourceHits
                ? String.format(Locale.ROOT,
                        "LOCAL: %d совпадений источников, CLOUD: %d — локальный стек не хуже.",
                        localSourceHits, cloudSourceHits)
                : String.format(Locale.ROOT,
                        "CLOUD: %d совпадений источников, LOCAL: %d.",
                        cloudSourceHits, localSourceHits);

        String stabilityVerdict = String.format(Locale.ROOT,
                "LOCAL %d/%d успешных, CLOUD %d/%d успешных.",
                localSuccess, count, cloudSuccess, count);

        return new RagLocalDemoRunResponse.RagLocalDemoRunSummaryDto(
                avgLocal,
                avgCloud,
                avgRetrieval,
                localWins,
                cloudWins,
                localSuccess,
                cloudSuccess,
                speedVerdict,
                qualityVerdict,
                stabilityVerdict);
    }

    private static int countSourceMatches(RagLocalDemoScenarioDto scenario, RagQueryResponse response) {
        if (scenario.expectedSources().isEmpty()) {
            return "UNKNOWN".equalsIgnoreCase(response.confidence()) ? 1 : 0;
        }
        int hits = 0;
        String answerLower = response.answer().toLowerCase(Locale.ROOT);
        for (String expected : scenario.expectedSources()) {
            if (answerLower.contains(expected.toLowerCase(Locale.ROOT))) {
                hits++;
                continue;
            }
            for (var source : response.sources()) {
                String section = source.section() != null ? source.section().toLowerCase(Locale.ROOT) : "";
                if (section.contains(expected.toLowerCase(Locale.ROOT))) {
                    hits++;
                    break;
                }
            }
        }
        return hits;
    }
}

package com.example.llmchat.localllm;

import com.example.llmchat.dto.LocalLlmOptimizationLastRunDto;
import com.example.llmchat.dto.LocalLlmOptimizationRunStatusDto;
import com.example.llmchat.dto.LocalLlmOptimizationCompareResponse;
import com.example.llmchat.dto.LocalLlmOptimizationDemoResponse;
import com.example.llmchat.dto.LocalLlmOptimizationRunResponse;
import com.example.llmchat.dto.LocalLlmOptimizationScenarioResultDto;
import com.example.llmchat.dto.LocalLlmProfileDto;
import com.example.llmchat.dto.RagLocalDemoScenarioDto;
import com.example.llmchat.rag.ChunkingStrategy;
import com.example.llmchat.rag.RagQueryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class LocalLlmOptimizationService {

    private static final List<RagLocalDemoScenarioDto> SCENARIOS = List.of(
            new RagLocalDemoScenarioDto(
                    1,
                    "Простой",
                    "Определение таинства",
                    "Что такое Крещение?",
                    List.of("Крещение", "таинство")),
            new RagLocalDemoScenarioDto(
                    2,
                    "Средний",
                    "Евхаристия",
                    "Когда и как было установлено таинство Евхаристии? Что означает слово «Евхаристия»?",
                    List.of("Евхаристия", "Тайная вечеря", "Литургия")),
            new RagLocalDemoScenarioDto(
                    3,
                    "Сложный",
                    "Семь таинств",
                    "Перечислите семь Таинств Православной Церкви и кратко назовите смысл каждого.",
                    List.of("Таинства", "Крещение", "Евхаристия", "Символ веры")),
            new RagLocalDemoScenarioDto(
                    4,
                    "Off-topic",
                    "Режим «не знаю»",
                    "Как приготовить борщ по рецепту из учебника?",
                    List.of()));

    private final LocalLlmService localLlmService;
    private final RagQueryService queryService;
    private final LocalLlmOptimizationRunStore runStore;
    private final ExecutorService runExecutor;
    private final LocalLlmProfile baselineProfile;
    private final LocalLlmProfile optimizedProfile;
    private final String useCase;

    private final Object runLock = new Object();
    private volatile boolean running;
    private volatile int currentStep;
    private volatile String currentScenarioTitle;
    private volatile long startedAtMs;
    private volatile String lastError;

    public LocalLlmOptimizationService(
            LocalLlmService localLlmService,
            RagQueryService queryService,
            LocalLlmOptimizationRunStore runStore,
            @Value("${app.local-llm.optimization.use-case}") String useCase,
            @Value("${app.local-llm.optimization.baseline.label}") String baselineLabel,
            @Value("${app.local-llm.optimization.baseline.model}") String baselineModel,
            @Value("${app.local-llm.optimization.baseline.temperature}") double baselineTemperature,
            @Value("${app.local-llm.optimization.baseline.max-tokens}") int baselineMaxTokens,
            @Value("${app.local-llm.optimization.baseline.context-window}") int baselineContextWindow,
            @Value("${app.local-llm.optimization.baseline.system-prompt}") String baselineSystemPrompt,
            @Value("${app.local-llm.optimization.baseline.quantization-note:}") String baselineQuantizationNote,
            @Value("${app.local-llm.optimization.optimized.label}") String optimizedLabel,
            @Value("${app.local-llm.optimization.optimized.model}") String optimizedModel,
            @Value("${app.local-llm.optimization.optimized.temperature}") double optimizedTemperature,
            @Value("${app.local-llm.optimization.optimized.max-tokens}") int optimizedMaxTokens,
            @Value("${app.local-llm.optimization.optimized.context-window}") int optimizedContextWindow,
            @Value("${app.local-llm.optimization.optimized.system-prompt}") String optimizedSystemPrompt,
            @Value("${app.local-llm.optimization.optimized.quantization-note:}") String optimizedQuantizationNote) {
        this.localLlmService = localLlmService;
        this.queryService = queryService;
        this.runStore = runStore;
        this.runExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "local-llm-optimization-run");
            thread.setDaemon(true);
            return thread;
        });
        this.useCase = useCase;
        this.baselineProfile = new LocalLlmProfile(
                baselineLabel,
                baselineModel,
                baselineTemperature,
                baselineMaxTokens,
                baselineContextWindow,
                baselineSystemPrompt,
                baselineQuantizationNote);
        this.optimizedProfile = new LocalLlmProfile(
                optimizedLabel,
                optimizedModel,
                optimizedTemperature,
                optimizedMaxTokens,
                optimizedContextWindow,
                optimizedSystemPrompt,
                optimizedQuantizationNote);
    }

    public LocalLlmOptimizationDemoResponse buildDemo() {
        var status = localLlmService.checkStatus();
        return new LocalLlmOptimizationDemoResponse(
                "День 29 — Оптимизация локальной LLM",
                "Сравнение базовой и оптимизированной конфигурации Ollama для RAG-ассистента "
                        + "по материалу «Основы православия».",
                useCase,
                toDto(baselineProfile, status.installedModels()),
                toDto(optimizedProfile, status.installedModels()),
                List.of(
                        "1. Базовая: " + baselineProfile.model()
                                + " · temp " + baselineProfile.temperature()
                                + " · max " + baselineProfile.maxTokens()
                                + " · ctx " + baselineProfile.contextWindow(),
                        "2. Оптимизированная: " + optimizedProfile.model()
                                + (optimizedProfile.quantizationNote() != null
                                        && !optimizedProfile.quantizationNote().isBlank()
                                        ? " (" + optimizedProfile.quantizationNote() + ")"
                                        : "")
                                + " · temp " + optimizedProfile.temperature()
                                + " · max " + optimizedProfile.maxTokens()
                                + " · ctx " + optimizedProfile.contextWindow(),
                        "3. Специализированный prompt-шаблон для экзаменационных Q&A",
                        "4. Один retrieval → две генерации (честное сравнение)",
                        "5. Метрики: качество терминов, скорость, токены"),
                List.of(
                        "Качество — совпадения с expectedSources сценария",
                        "Скорость — generationDurationMs",
                        "Ресурсы — tokenCount и отношение baseline/optimized"),
                SCENARIOS);
    }

    public List<RagLocalDemoScenarioDto> scenarios() {
        return SCENARIOS;
    }

    public LocalLlmOptimizationRunStatusDto startRunAsync() {
        synchronized (runLock) {
            if (running) {
                return buildRunStatus();
            }
            running = true;
            currentStep = 1;
            lastError = null;
            startedAtMs = System.currentTimeMillis();
            currentScenarioTitle = SCENARIOS.isEmpty() ? null : SCENARIOS.get(0).title();
        }
        runExecutor.submit(this::runAllBackground);
        return buildRunStatus();
    }

    public LocalLlmOptimizationRunStatusDto runStatus() {
        return buildRunStatus();
    }

    public LocalLlmOptimizationLastRunDto lastRun() {
        return runStore.load();
    }

    private void runAllBackground() {
        try {
            LocalLlmOptimizationRunResponse response = runAllInternalWithProgress();
            runStore.save(new LocalLlmOptimizationLastRunDto(System.currentTimeMillis(), response));
        } catch (Exception exception) {
            synchronized (runLock) {
                lastError = exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName();
            }
        } finally {
            synchronized (runLock) {
                running = false;
                currentStep = 0;
                currentScenarioTitle = null;
            }
        }
    }

    private LocalLlmOptimizationRunResponse runAllInternal() {
        long startedAt = System.currentTimeMillis();
        List<LocalLlmOptimizationScenarioResultDto> results = SCENARIOS.stream()
                .map(this::runScenarioInternal)
                .toList();
        return buildRunResponse(results, System.currentTimeMillis() - startedAt);
    }

    private LocalLlmOptimizationRunResponse runAllInternalWithProgress() {
        long startedAt = System.currentTimeMillis();
        List<LocalLlmOptimizationScenarioResultDto> results = new ArrayList<>();
        for (int index = 0; index < SCENARIOS.size(); index++) {
            RagLocalDemoScenarioDto scenario = SCENARIOS.get(index);
            synchronized (runLock) {
                currentStep = index + 1;
                currentScenarioTitle = scenario.title();
            }
            results.add(runScenarioInternal(scenario));
        }
        return buildRunResponse(results, System.currentTimeMillis() - startedAt);
    }

    private LocalLlmOptimizationRunResponse buildRunResponse(
            List<LocalLlmOptimizationScenarioResultDto> results,
            long totalDurationMs) {
        return new LocalLlmOptimizationRunResponse(
                results.size(),
                totalDurationMs,
                buildSummary(results),
                results);
    }

    private LocalLlmOptimizationRunStatusDto buildRunStatus() {
        LocalLlmOptimizationLastRunDto lastRun = runStore.load();
        synchronized (runLock) {
            return new LocalLlmOptimizationRunStatusDto(
                    running,
                    currentStep,
                    SCENARIOS.size(),
                    currentScenarioTitle,
                    startedAtMs,
                    lastError,
                    lastRun != null,
                    lastRun != null ? lastRun.completedAtMs() : null);
        }
    }

    public LocalLlmOptimizationScenarioResultDto runScenario(int scenarioId) {
        RagLocalDemoScenarioDto scenario = SCENARIOS.stream()
                .filter(item -> item.id() == scenarioId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown scenario id: " + scenarioId));
        return runScenarioInternal(scenario);
    }

    public LocalLlmOptimizationCompareResponse compareQuestion(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question is required");
        }
        return queryService.compareLocalProfiles(
                question.trim(),
                baselineProfile,
                optimizedProfile,
                ChunkingStrategy.STRUCTURE,
                null,
                null,
                List.of());
    }

    public LocalLlmProfile baselineProfile() {
        return baselineProfile;
    }

    public LocalLlmProfile optimizedProfile() {
        return optimizedProfile;
    }

    private LocalLlmOptimizationScenarioResultDto runScenarioInternal(RagLocalDemoScenarioDto scenario) {
        LocalLlmOptimizationCompareResponse compare = queryService.compareLocalProfiles(
                scenario.question(),
                baselineProfile,
                optimizedProfile,
                ChunkingStrategy.STRUCTURE,
                null,
                null,
                scenario.expectedSources());
        return new LocalLlmOptimizationScenarioResultDto(scenario, compare);
    }

    private LocalLlmOptimizationRunResponse.LocalLlmOptimizationRunSummaryDto buildSummary(
            List<LocalLlmOptimizationScenarioResultDto> results) {
        long baselineMsSum = 0;
        long optimizedMsSum = 0;
        long retrievalMsSum = 0;
        int baselineWins = 0;
        int optimizedWins = 0;
        int baselineSuccess = 0;
        int optimizedSuccess = 0;
        int baselineMatches = 0;
        int optimizedMatches = 0;
        long baselineTokens = 0;
        long optimizedTokens = 0;

        for (LocalLlmOptimizationScenarioResultDto result : results) {
            var summary = result.compare().summary();
            baselineMsSum += summary.baselineGenerationMs();
            optimizedMsSum += summary.optimizedGenerationMs();
            retrievalMsSum += summary.retrievalMs();
            baselineTokens += summary.baselineTokens();
            optimizedTokens += summary.optimizedTokens();
            baselineMatches += summary.baselineSourceMatches();
            optimizedMatches += summary.optimizedSourceMatches();

            if (summary.baselineSuccess()) {
                baselineSuccess++;
            }
            if (summary.optimizedSuccess()) {
                optimizedSuccess++;
            }
            if ("BASELINE".equals(summary.speedWinner())) {
                baselineWins++;
            } else if ("OPTIMIZED".equals(summary.speedWinner())) {
                optimizedWins++;
            }
        }

        int count = results.size();
        long avgBaseline = count == 0 ? 0 : Math.round((double) baselineMsSum / count);
        long avgOptimized = count == 0 ? 0 : Math.round((double) optimizedMsSum / count);
        long avgRetrieval = count == 0 ? 0 : Math.round((double) retrievalMsSum / count);

        String speedVerdict = avgOptimized < avgBaseline
                ? String.format(Locale.ROOT, "OPTIMIZED быстрее в среднем (%d vs %d ms)", avgOptimized, avgBaseline)
                : String.format(Locale.ROOT, "BASELINE быстрее в среднем (%d vs %d ms)", avgBaseline, avgOptimized);

        String qualityVerdict = optimizedMatches >= baselineMatches
                ? String.format(Locale.ROOT,
                        "OPTIMIZED: %d совпадений терминов, BASELINE: %d — качество не хуже.",
                        optimizedMatches, baselineMatches)
                : String.format(Locale.ROOT,
                        "BASELINE: %d совпадений, OPTIMIZED: %d.",
                        baselineMatches, optimizedMatches);

        String resourceVerdict = String.format(Locale.ROOT,
                "Токены: BASELINE %d, OPTIMIZED %d (экономия %.0f%%).",
                baselineTokens,
                optimizedTokens,
                baselineTokens > 0 ? (1.0 - (double) optimizedTokens / baselineTokens) * 100.0 : 0.0);

        return new LocalLlmOptimizationRunResponse.LocalLlmOptimizationRunSummaryDto(
                avgBaseline,
                avgOptimized,
                avgRetrieval,
                baselineWins,
                optimizedWins,
                baselineSuccess,
                optimizedSuccess,
                baselineTokens,
                optimizedTokens,
                speedVerdict,
                qualityVerdict,
                resourceVerdict);
    }

    private static LocalLlmProfileDto toDto(LocalLlmProfile profile, List<String> installedModels) {
        return new LocalLlmProfileDto(
                profile.label(),
                profile.model(),
                profile.temperature(),
                profile.maxTokens(),
                profile.contextWindow(),
                profile.systemPrompt(),
                profile.quantizationNote(),
                isModelAvailable(installedModels, profile.model()));
    }

    private static boolean isModelAvailable(List<String> installedModels, String model) {
        if (installedModels == null || model == null || model.isBlank()) {
            return false;
        }
        for (String installed : installedModels) {
            if (installed != null && (installed.equals(model) || installed.startsWith(model + ":"))) {
                return true;
            }
        }
        return false;
    }
}

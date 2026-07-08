package com.example.llmchat.localllm;

import com.example.llmchat.dto.LocalLlmChatResponse;
import com.example.llmchat.dto.LocalLlmDemoResponse;
import com.example.llmchat.dto.LocalLlmDemoRunResponse;
import com.example.llmchat.dto.LocalLlmDemoScenarioDto;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LocalLlmDemoService {

    private static final List<LocalLlmDemoScenarioDto> SCENARIOS = List.of(
            new LocalLlmDemoScenarioDto(
                    1,
                    "Простой",
                    "Сколько будет 2 + 2? Ответ одним словом."),
            new LocalLlmDemoScenarioDto(
                    2,
                    "Средний",
                    "Объясни в трёх предложениях, что такое православный пост."),
            new LocalLlmDemoScenarioDto(
                    3,
                    "Сложный",
                    "Сравни три основных периода поста в православном календаре: "
                            + "таблицей из 3 строк (период, продолжительность, отличие)."),
            new LocalLlmDemoScenarioDto(
                    4,
                    "Простой",
                    "Сколько книг в Новом Завете? Ответ только числом."),
            new LocalLlmDemoScenarioDto(
                    5,
                    "Средний",
                    "В чём разница между храмовым праздником и двунадесятым? "
                            + "Ответ в 3–4 предложениях."),
            new LocalLlmDemoScenarioDto(
                    6,
                    "Сложный",
                    "Составь план подготовки к экзамену по «Основам православия» на 5 дней: "
                            + "таблица из 5 строк (день, тема, что выучить)."),
            new LocalLlmDemoScenarioDto(
                    7,
                    "Рассуждение",
                    "Можно ли есть рыбу в Великий пост по средам и пятницам? "
                            + "Ответ: да или нет и краткое обоснование в 2–3 предложениях."));

    private final LocalLlmService localLlmService;

    public LocalLlmDemoService(LocalLlmService localLlmService) {
        this.localLlmService = localLlmService;
    }

    public LocalLlmDemoResponse buildDemo() {
        return new LocalLlmDemoResponse(
                "День 26 — Локальная LLM (Ollama)",
                "Установка и проверка локальной модели через CLI и HTTP API.",
                localLlmService.baseUrl(),
                localLlmService.model(),
                localLlmService.temperature(),
                localLlmService.maxTokens(),
                List.of(
                        "1. Установка Ollama (Windows)",
                        "2. Загрузка модели: ollama pull " + localLlmService.model(),
                        "3. Проверка CLI: ollama run " + localLlmService.model(),
                        "4. HTTP API: POST /api/chat на " + localLlmService.baseUrl(),
                        "5. Запросы разной сложности через backend и UI"),
                SCENARIOS);
    }

    public List<LocalLlmDemoScenarioDto> scenarios() {
        return SCENARIOS;
    }

    public LocalLlmDemoRunResponse runAll() {
        long startedAt = System.currentTimeMillis();
        List<LocalLlmChatResponse> results = SCENARIOS.stream()
                .map(scenario -> localLlmService.chat(scenario.prompt()))
                .toList();
        long totalDurationMs = System.currentTimeMillis() - startedAt;
        return new LocalLlmDemoRunResponse(localLlmService.model(), totalDurationMs, results);
    }

    public LocalLlmChatResponse runScenario(int scenarioId) {
        LocalLlmDemoScenarioDto scenario = SCENARIOS.stream()
                .filter(item -> item.id() == scenarioId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown scenario id: " + scenarioId));
        return localLlmService.chat(scenario.prompt());
    }
}

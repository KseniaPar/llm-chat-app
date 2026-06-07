package com.example.llmchat.controller;

import com.example.llmchat.dto.ChatRequest;
import com.example.llmchat.dto.ChatResponse;
import com.example.llmchat.dto.CompareResponse;
import com.example.llmchat.dto.CompareResult;
import com.example.llmchat.dto.LlmResult;
import com.example.llmchat.dto.ModelAnalysisRequest;
import com.example.llmchat.dto.ModelCallResponse;
import com.example.llmchat.dto.ModelCompareResponse;
import com.example.llmchat.dto.ModelCompareResult;
import com.example.llmchat.dto.ModelRequest;
import com.example.llmchat.dto.ReasoningCompareResponse;
import com.example.llmchat.dto.ReasoningCompareResult;
import com.example.llmchat.dto.TemperatureAnalysisRequest;
import com.example.llmchat.dto.TemperatureCompareResponse;
import com.example.llmchat.dto.TemperatureCompareResult;
import com.example.llmchat.dto.TemperatureRequest;
import com.example.llmchat.service.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final LlmService llmService;

    public ChatController(LlmService llmService) {
        this.llmService = llmService;
    }

    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        log.info("POST /api/chat — prompt length: {}", request.prompt().length());

        LlmResult result = llmService.ask(request.prompt());

        return new ChatResponse(result.response(), result.logs());
    }

    @PostMapping("/compare")
    public CompareResponse compare(@RequestBody ChatRequest request) {
        log.info("POST /api/chat/compare — prompt length: {}", request.prompt().length());

        CompareResult result = llmService.compare(request.prompt());

        return new CompareResponse(
                result.unrestricted(),
                result.formatOnly(),
                result.lengthOnly(),
                result.stopOnly(),
                result.fullControl(),
                result.logs());
    }

    @PostMapping("/temperature")
    public ChatResponse temperature(@RequestBody TemperatureRequest request) {
        log.info("POST /api/chat/temperature — prompt length: {}, temperature: {}",
                request.prompt().length(), request.temperature());

        LlmResult result = llmService.askWithTemperature(request.prompt(), request.temperature());

        return new ChatResponse(result.response(), result.logs());
    }

    @PostMapping("/compare-temperature-analysis")
    public ChatResponse compareTemperatureAnalysis(@RequestBody TemperatureAnalysisRequest request) {
        log.info("POST /api/chat/compare-temperature-analysis — prompt length: {}",
                request.prompt().length());

        LlmResult result = llmService.analyzeTemperature(request);

        return new ChatResponse(result.response(), result.logs());
    }

    @PostMapping("/compare-temperature")
    public TemperatureCompareResponse compareTemperature(@RequestBody ChatRequest request) {
        log.info("POST /api/chat/compare-temperature — prompt length: {}", request.prompt().length());

        TemperatureCompareResult result = llmService.compareTemperature(request.prompt());

        return new TemperatureCompareResponse(
                result.temp0(),
                result.temp07(),
                result.temp12(),
                result.comparison(),
                result.logs());
    }

    @PostMapping("/compare-reasoning")
    public ReasoningCompareResponse compareReasoning(@RequestBody ChatRequest request) {
        log.info("POST /api/chat/compare-reasoning — prompt length: {}", request.prompt().length());

        ReasoningCompareResult result = llmService.compareReasoning(request.prompt());

        return new ReasoningCompareResponse(
                result.direct(),
                result.stepByStep(),
                result.metaPrompt(),
                result.metaPromptAnswer(),
                result.experts(),
                result.comparison(),
                result.logs());
    }

    @PostMapping("/model")
    public ModelCallResponse model(@RequestBody ModelRequest request) {
        log.info("POST /api/chat/model — prompt length: {}, tier: {}",
                request.prompt().length(), request.tier());

        return llmService.askWithModel(request.prompt(), request.tier());
    }

    @PostMapping("/compare-models-analysis")
    public ChatResponse compareModelsAnalysis(@RequestBody ModelAnalysisRequest request) {
        log.info("POST /api/chat/compare-models-analysis — prompt length: {}",
                request.prompt().length());

        LlmResult result = llmService.analyzeModels(request);

        return new ChatResponse(result.response(), result.logs());
    }

    @PostMapping("/compare-models")
    public ModelCompareResponse compareModels(@RequestBody ChatRequest request) {
        log.info("POST /api/chat/compare-models — prompt length: {}", request.prompt().length());

        ModelCompareResult result = llmService.compareModels(request.prompt());

        return new ModelCompareResponse(
                result.weak(),
                result.medium(),
                result.strong(),
                result.comparison(),
                result.logs());
    }
}

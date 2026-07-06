package com.example.llmchat.controller;

import com.example.llmchat.dto.RagChatMessageDto;
import com.example.llmchat.dto.RagChatRequest;
import com.example.llmchat.dto.RagChatResponse;
import com.example.llmchat.dto.RagCompareResponse;
import com.example.llmchat.dto.RagDemoResponse;
import com.example.llmchat.dto.RagDialogMemoryDto;
import com.example.llmchat.dto.RagEvalQuestionDto;
import com.example.llmchat.dto.RagEvalResponse;
import com.example.llmchat.dto.RagEvalValidationResponse;
import com.example.llmchat.dto.RagModeCompareResponse;
import com.example.llmchat.dto.RagModeEvalResponse;
import com.example.llmchat.dto.RagIndexRequest;
import com.example.llmchat.dto.RagIndexResponse;
import com.example.llmchat.dto.RagQueryCompareResponse;
import com.example.llmchat.dto.RagQueryRequest;
import com.example.llmchat.dto.RagQueryResponse;
import com.example.llmchat.dto.RagScenarioDto;
import com.example.llmchat.dto.RagStatsResponse;
import com.example.llmchat.rag.ChunkingStrategy;
import com.example.llmchat.rag.RagChatService;
import com.example.llmchat.rag.RagCorpusLoader;
import com.example.llmchat.rag.RagDemoService;
import com.example.llmchat.rag.RagEvalService;
import com.example.llmchat.rag.RagIndexRepository;
import com.example.llmchat.rag.RagIndexingService;
import com.example.llmchat.rag.RagQueryService;
import com.example.llmchat.rag.RagScenarioLoader;
import com.example.llmchat.rag.RagRetrievalMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private final RagIndexingService indexingService;
    private final RagIndexRepository indexRepository;
    private final RagCorpusLoader corpusLoader;
    private final RagQueryService queryService;
    private final RagDemoService demoService;
    private final RagEvalService evalService;
    private final RagChatService chatService;
    private final RagScenarioLoader scenarioLoader;

    public RagController(
            RagIndexingService indexingService,
            RagIndexRepository indexRepository,
            RagCorpusLoader corpusLoader,
            RagQueryService queryService,
            RagDemoService demoService,
            RagEvalService evalService,
            RagChatService chatService,
            RagScenarioLoader scenarioLoader) {
        this.indexingService = indexingService;
        this.indexRepository = indexRepository;
        this.corpusLoader = corpusLoader;
        this.queryService = queryService;
        this.demoService = demoService;
        this.evalService = evalService;
        this.chatService = chatService;
        this.scenarioLoader = scenarioLoader;
    }

    @PostMapping("/index")
    public RagIndexResponse index(@RequestBody RagIndexRequest request) {
        log.info("POST /api/rag/index strategy={}", request.strategy());
        try {
            return RagIndexResponse.from(indexingService.index(request.strategy()));
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PostMapping("/index/compare")
    public RagCompareResponse compareIndex() {
        log.info("POST /api/rag/index/compare");
        try {
            return RagCompareResponse.from(indexingService.compareStrategies());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @GetMapping("/index/stats")
    public RagStatsResponse stats() {
        RagCorpusLoader.CorpusStats corpus = corpusLoader.stats();
        return new RagStatsResponse(
                corpus.documentCount(),
                corpus.estimatedPages(),
                indexRepository.allStats());
    }

    @GetMapping("/index/demo")
    public RagDemoResponse demo() {
        return demoService.buildDemo();
    }

    @PostMapping("/query")
    public RagQueryResponse query(@RequestBody RagQueryRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question is required");
        }
        ChunkingStrategy strategy = request.strategy() != null ? request.strategy() : ChunkingStrategy.STRUCTURE;
        boolean useRag = request.useRag() == null || request.useRag();
        RagRetrievalMode mode = request.mode() != null ? request.mode() : RagRetrievalMode.FILTERED;
        log.info("POST /api/rag/query useRag={} strategy={} mode={}", useRag, strategy, mode);
        try {
            return queryService.query(
                    request.question(), useRag, strategy, request.topK(), mode, request.minSimilarity());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PostMapping("/query/compare")
    public RagQueryCompareResponse queryCompare(@RequestBody RagQueryRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question is required");
        }
        ChunkingStrategy strategy = request.strategy() != null ? request.strategy() : ChunkingStrategy.STRUCTURE;
        log.info("POST /api/rag/query/compare strategy={}", strategy);
        try {
            return queryService.compare(request.question(), strategy, request.topK());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PostMapping("/query/modes/compare")
    public RagModeCompareResponse queryModesCompare(@RequestBody RagQueryRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question is required");
        }
        ChunkingStrategy strategy = request.strategy() != null ? request.strategy() : ChunkingStrategy.STRUCTURE;
        log.info("POST /api/rag/query/modes/compare strategy={}", strategy);
        try {
            return queryService.compareModes(
                    request.question(), strategy, request.topK(), request.minSimilarity());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @GetMapping("/eval/questions")
    public List<RagEvalQuestionDto> evalQuestions() {
        return evalService.questions();
    }

    @PostMapping("/eval/run")
    public RagEvalResponse runEval(@RequestBody(required = false) RagQueryRequest request) {
        ChunkingStrategy strategy = request != null && request.strategy() != null
                ? request.strategy()
                : ChunkingStrategy.STRUCTURE;
        Integer topK = request != null ? request.topK() : null;
        log.info("POST /api/rag/eval/run strategy={}", strategy);
        try {
            return evalService.runEval(strategy, topK);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PostMapping("/eval/modes/run")
    public RagModeEvalResponse runModeEval(@RequestBody(required = false) RagQueryRequest request) {
        ChunkingStrategy strategy = request != null && request.strategy() != null
                ? request.strategy()
                : ChunkingStrategy.STRUCTURE;
        Integer topK = request != null ? request.topK() : null;
        Double minSimilarity = request != null ? request.minSimilarity() : null;
        log.info("POST /api/rag/eval/modes/run strategy={}", strategy);
        try {
            return evalService.runModeEval(strategy, topK, minSimilarity);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PostMapping("/eval/validate")
    public RagEvalValidationResponse runCitationValidation(@RequestBody(required = false) RagQueryRequest request) {
        ChunkingStrategy strategy = request != null && request.strategy() != null
                ? request.strategy()
                : ChunkingStrategy.STRUCTURE;
        Integer topK = request != null ? request.topK() : null;
        Double minSimilarity = request != null ? request.minSimilarity() : null;
        log.info("POST /api/rag/eval/validate strategy={}", strategy);
        try {
            return evalService.runCitationValidation(strategy, topK, minSimilarity);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @GetMapping("/scenarios")
    public List<RagScenarioDto> scenarios() {
        return scenarioLoader.loadAll();
    }

    @PostMapping("/chat")
    public RagChatResponse chat(@RequestBody RagChatRequest request) {
        if (request.message() == null || request.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
        }
        log.info("POST /api/rag/chat sessionId={}", request.sessionId());
        try {
            return chatService.chat(request);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @GetMapping("/chat/history")
    public List<RagChatMessageDto> chatHistory(@RequestParam String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId is required");
        }
        return chatService.history(sessionId);
    }

    @GetMapping("/chat/memory")
    public RagDialogMemoryDto chatMemory(@RequestParam String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId is required");
        }
        return chatService.memory(sessionId);
    }

    @PostMapping("/chat/reset")
    public Map<String, String> chatReset(@RequestBody(required = false) RagChatRequest request) {
        String sessionId = request != null ? request.sessionId() : null;
        log.info("POST /api/rag/chat/reset sessionId={}", sessionId);
        return Map.of("sessionId", chatService.reset(sessionId));
    }
}

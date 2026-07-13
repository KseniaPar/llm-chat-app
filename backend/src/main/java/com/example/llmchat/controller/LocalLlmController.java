package com.example.llmchat.controller;

import com.example.llmchat.dto.LocalLlmChatRequest;
import com.example.llmchat.dto.LocalLlmChatResponse;
import com.example.llmchat.dto.LocalLlmOptimizationCompareResponse;
import com.example.llmchat.dto.LocalLlmOptimizationLastRunDto;
import com.example.llmchat.dto.LocalLlmOptimizationRunStatusDto;
import com.example.llmchat.dto.LocalLlmOptimizationDemoResponse;
import com.example.llmchat.dto.LocalLlmOptimizationScenarioResultDto;
import com.example.llmchat.dto.LocalLlmDemoResponse;
import com.example.llmchat.dto.LocalLlmDemoRunResponse;
import com.example.llmchat.dto.LocalLlmStatusResponse;
import com.example.llmchat.dto.LocalLlmAgentChatRequest;
import com.example.llmchat.dto.LocalLlmAgentChatResponse;
import com.example.llmchat.dto.LocalLlmAgentHistoryResponse;
import com.example.llmchat.dto.LocalLlmAgentResetRequest;
import com.example.llmchat.dto.LocalLlmServiceChatResponse;
import com.example.llmchat.dto.LocalLlmServiceInfoResponse;
import com.example.llmchat.dto.LocalLlmServiceVerifyResponse;
import com.example.llmchat.localllm.LocalLlmAgentService;
import com.example.llmchat.localllm.LocalLlmDemoService;
import com.example.llmchat.localllm.LocalLlmOptimizationService;
import com.example.llmchat.localllm.LocalLlmPrivateService;
import com.example.llmchat.localllm.LocalLlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/local-llm")
public class LocalLlmController {

    private static final Logger log = LoggerFactory.getLogger(LocalLlmController.class);

    private final LocalLlmService localLlmService;
    private final LocalLlmDemoService demoService;
    private final LocalLlmAgentService agentService;
    private final LocalLlmOptimizationService optimizationService;
    private final LocalLlmPrivateService privateService;

    public LocalLlmController(
            LocalLlmService localLlmService,
            LocalLlmDemoService demoService,
            LocalLlmAgentService agentService,
            LocalLlmOptimizationService optimizationService,
            LocalLlmPrivateService privateService) {
        this.localLlmService = localLlmService;
        this.demoService = demoService;
        this.agentService = agentService;
        this.optimizationService = optimizationService;
        this.privateService = privateService;
    }

    @GetMapping("/status")
    public LocalLlmStatusResponse status() {
        return localLlmService.checkStatus();
    }

    @GetMapping("/demo")
    public LocalLlmDemoResponse demo() {
        return demoService.buildDemo();
    }

    @PostMapping("/chat")
    public LocalLlmChatResponse chat(@RequestBody LocalLlmChatRequest request) {
        log.info("POST /api/local-llm/chat promptLength={}",
                request.prompt() != null ? request.prompt().length() : 0);
        return localLlmService.chat(request.prompt());
    }

    @PostMapping("/agent/chat")
    public LocalLlmAgentChatResponse agentChat(@RequestBody LocalLlmAgentChatRequest request) {
        log.info("POST /api/local-llm/agent/chat promptLength={}, sessionId={}",
                request.prompt() != null ? request.prompt().length() : 0,
                request.sessionId());
        return agentService.chat(request.prompt(), request.sessionId());
    }

    @GetMapping("/agent/history")
    public LocalLlmAgentHistoryResponse agentHistory(@RequestParam String sessionId) {
        log.info("GET /api/local-llm/agent/history sessionId={}", sessionId);
        return agentService.history(sessionId);
    }

    @PostMapping("/agent/reset")
    public void agentReset(@RequestBody LocalLlmAgentResetRequest request) {
        log.info("POST /api/local-llm/agent/reset sessionId={}", request.sessionId());
        agentService.reset(request.sessionId());
    }

    @PostMapping("/demo/run")
    public LocalLlmDemoRunResponse runDemo() {
        log.info("POST /api/local-llm/demo/run");
        return demoService.runAll();
    }

    @PostMapping("/demo/run/{scenarioId}")
    public LocalLlmChatResponse runScenario(@PathVariable int scenarioId) {
        log.info("POST /api/local-llm/demo/run/{}", scenarioId);
        return demoService.runScenario(scenarioId);
    }

    @GetMapping("/optimization/demo")
    public LocalLlmOptimizationDemoResponse optimizationDemo() {
        return optimizationService.buildDemo();
    }

    @PostMapping("/optimization/compare")
    public LocalLlmOptimizationCompareResponse optimizationCompare(@RequestBody LocalLlmChatRequest request) {
        log.info("POST /api/local-llm/optimization/compare promptLength={}",
                request.prompt() != null ? request.prompt().length() : 0);
        return optimizationService.compareQuestion(request.prompt());
    }

    @PostMapping("/optimization/run")
    public LocalLlmOptimizationRunStatusDto optimizationRunAll() {
        log.info("POST /api/local-llm/optimization/run (async)");
        return optimizationService.startRunAsync();
    }

    @GetMapping("/optimization/run/status")
    public LocalLlmOptimizationRunStatusDto optimizationRunStatus() {
        return optimizationService.runStatus();
    }

    @GetMapping("/optimization/last-run")
    public ResponseEntity<LocalLlmOptimizationLastRunDto> optimizationLastRun() {
        LocalLlmOptimizationLastRunDto lastRun = optimizationService.lastRun();
        if (lastRun == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(lastRun);
    }

    @PostMapping("/optimization/run/{scenarioId}")
    public LocalLlmOptimizationScenarioResultDto optimizationRunScenario(@PathVariable int scenarioId) {
        log.info("POST /api/local-llm/optimization/run/{}", scenarioId);
        return optimizationService.runScenario(scenarioId);
    }

    @GetMapping("/service/info")
    public LocalLlmServiceInfoResponse serviceInfo() {
        return privateService.info();
    }

    @PostMapping("/service/chat")
    public LocalLlmServiceChatResponse serviceChat(
            @RequestBody LocalLlmChatRequest request,
            HttpServletRequest httpRequest,
            @RequestHeader(value = "X-Local-Llm-Api-Key", required = false) String apiKey) {
        log.info("POST /api/local-llm/service/chat promptLength={}, client={}",
                request.prompt() != null ? request.prompt().length() : 0,
                httpRequest.getRemoteAddr());
        return privateService.chat(request.prompt(), httpRequest.getRemoteAddr(), apiKey);
    }

    @PostMapping("/service/verify")
    public LocalLlmServiceVerifyResponse serviceVerify() {
        log.info("POST /api/local-llm/service/verify");
        return privateService.verify();
    }
}

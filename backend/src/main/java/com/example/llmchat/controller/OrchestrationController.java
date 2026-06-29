package com.example.llmchat.controller;

import com.example.llmchat.dto.OrchestrationRunRequest;
import com.example.llmchat.dto.OrchestrationRunResponse;
import com.example.llmchat.mcp.McpOrchestrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/mcp/orchestration")
public class OrchestrationController {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationController.class);

    private final McpOrchestrationService orchestrationService;

    public OrchestrationController(McpOrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    @PostMapping("/run")
    public OrchestrationRunResponse run(@RequestBody OrchestrationRunRequest request) {
        log.info(
                "POST /api/mcp/orchestration/run scenario='{}' query='{}' filename='{}'",
                request.scenarioId(),
                request.query(),
                request.filename());
        try {
            return orchestrationService.run(request);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), exception);
        }
    }
}

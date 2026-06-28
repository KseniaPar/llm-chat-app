package com.example.llmchat.controller;

import com.example.llmchat.dto.PipelineRunRequest;
import com.example.llmchat.dto.PipelineRunResponse;
import com.example.llmchat.mcp.McpPipelineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/mcp/pipeline")
public class PipelineController {

    private static final Logger log = LoggerFactory.getLogger(PipelineController.class);

    private final McpPipelineService pipelineService;

    public PipelineController(McpPipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    @PostMapping("/run")
    public PipelineRunResponse run(@RequestBody PipelineRunRequest request) {
        log.info("POST /api/mcp/pipeline/run query='{}' filename='{}'", request.query(), request.filename());
        try {
            return pipelineService.run(request.query(), request.filename());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), exception);
        }
    }
}

package com.example.llmchat.controller;

import com.example.llmchat.dto.FileAssistChatRequest;
import com.example.llmchat.dto.FileAssistChatResponse;
import com.example.llmchat.dto.FileAssistStatusResponse;
import com.example.llmchat.fileassist.FileAssistantService;
import com.example.llmchat.fileassist.FilePathGuard;
import com.example.llmchat.mcp.McpConnectionService;
import com.example.llmchat.platform.PlatformServerService;
import com.example.llmchat.rag.RagCompletionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/files")
public class FileAssistController {

    private static final Logger log = LoggerFactory.getLogger(FileAssistController.class);

    private static final List<String> WRITE_ALLOWLIST = List.of(
            "project/docs/**",
            "docs/**",
            "adr/**",
            "README.md",
            "CHANGELOG.md");

    private final FileAssistantService fileAssistantService;
    private final FilePathGuard filePathGuard;
    private final McpConnectionService mcpConnectionService;
    private final PlatformServerService platformServerService;
    private final RagCompletionService ragCompletionService;

    public FileAssistController(
            FileAssistantService fileAssistantService,
            FilePathGuard filePathGuard,
            McpConnectionService mcpConnectionService,
            PlatformServerService platformServerService,
            RagCompletionService ragCompletionService) {
        this.fileAssistantService = fileAssistantService;
        this.filePathGuard = filePathGuard;
        this.mcpConnectionService = mcpConnectionService;
        this.platformServerService = platformServerService;
        this.ragCompletionService = ragCompletionService;
    }

    @GetMapping("/status")
    public FileAssistStatusResponse status() {
        var mcpStatus = mcpConnectionService.getToolsSnapshot();
        String root = filePathGuard.repoRoot().toString();
        return new FileAssistStatusResponse(
                platformServerService.info().version(),
                fileAssistantService.cloudConfigured(),
                ragCompletionService.cloudModel(),
                mcpStatus.connected(),
                true,
                root,
                WRITE_ALLOWLIST);
    }

    @PostMapping("/goal")
    public FileAssistChatResponse goal(@RequestBody FileAssistChatRequest request) {
        String goal = request != null ? request.goal() : null;
        boolean dryRun = request != null && Boolean.TRUE.equals(request.dryRun());
        if (goal == null || goal.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "goal is required");
        }
        log.info("POST /api/files/goal dryRun={} goalLen={}", dryRun, goal.trim().length());
        try {
            FileAssistantService.FileAnswer answer = fileAssistantService.executeGoal(goal.trim(), dryRun);
            return new FileAssistChatResponse(
                    answer.goal(),
                    answer.answer(),
                    answer.model(),
                    answer.durationMs(),
                    answer.dryRun(),
                    answer.appliedPaths(),
                    answer.writes(),
                    answer.mcpToolCalls());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }
}

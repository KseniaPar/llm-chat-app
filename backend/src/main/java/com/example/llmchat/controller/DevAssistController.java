package com.example.llmchat.controller;

import com.example.llmchat.devassist.DeveloperAssistantService;
import com.example.llmchat.devassist.DevAssistLlmConfig;
import com.example.llmchat.devassist.GitMcpFacade;
import com.example.llmchat.dto.DevAssistChatRequest;
import com.example.llmchat.dto.DevAssistChatResponse;
import com.example.llmchat.dto.DevAssistStatusResponse;
import com.example.llmchat.localllm.LocalLlmService;
import com.example.llmchat.mcp.McpConnectionService;
import com.example.llmchat.platform.PlatformServerService;
import com.example.llmchat.rag.ProjectDocsIndexingService;
import com.example.llmchat.rag.RagCompletionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Locale;

@RestController
@RequestMapping("/api/devassist")
public class DevAssistController {

    private static final Logger log = LoggerFactory.getLogger(DevAssistController.class);

    private final DeveloperAssistantService developerAssistantService;
    private final ProjectDocsIndexingService projectDocsIndexingService;
    private final GitMcpFacade gitMcpFacade;
    private final LocalLlmService localLlmService;
    private final RagCompletionService ragCompletionService;
    private final DevAssistLlmConfig devAssistLlmConfig;
    private final McpConnectionService mcpConnectionService;
    private final PlatformServerService platformServerService;
    private final ObjectMapper objectMapper;

    public DevAssistController(
            DeveloperAssistantService developerAssistantService,
            ProjectDocsIndexingService projectDocsIndexingService,
            GitMcpFacade gitMcpFacade,
            LocalLlmService localLlmService,
            RagCompletionService ragCompletionService,
            DevAssistLlmConfig devAssistLlmConfig,
            McpConnectionService mcpConnectionService,
            PlatformServerService platformServerService,
            ObjectMapper objectMapper) {
        this.developerAssistantService = developerAssistantService;
        this.projectDocsIndexingService = projectDocsIndexingService;
        this.gitMcpFacade = gitMcpFacade;
        this.localLlmService = localLlmService;
        this.ragCompletionService = ragCompletionService;
        this.devAssistLlmConfig = devAssistLlmConfig;
        this.mcpConnectionService = mcpConnectionService;
        this.platformServerService = platformServerService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/status")
    public DevAssistStatusResponse status() {
        var mcpStatus = mcpConnectionService.getToolsSnapshot();
        var projectStatus = projectDocsIndexingService.status();
        var platform = platformServerService.info();

        GitBranchInfo branchInfo = loadGitBranch();
        boolean gitToolAvailable = mcpStatus.tools().stream()
                .anyMatch(tool -> "getCurrentBranch".equals(tool.name()));

        return new DevAssistStatusResponse(
                platform.version(),
                devAssistLlmConfig.isReady(localLlmService),
                devAssistLlmConfig.modelName(ragCompletionService, localLlmService),
                devAssistLlmConfig.providerLabel(),
                mcpStatus.connected(),
                mcpStatus.toolCount(),
                gitToolAvailable,
                branchInfo.branch(),
                branchInfo.commit(),
                branchInfo.repoRoot() != null ? branchInfo.repoRoot() : projectStatus.repoRoot(),
                projectStatus.ready(),
                projectStatus.documentCount(),
                projectStatus.chunkCount(),
                projectStatus.indexPath());
    }

    @PostMapping("/chat")
    public DevAssistChatResponse chat(@RequestBody DevAssistChatRequest request) {
        String question = request != null ? request.question() : null;
        if (question == null || question.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question is required");
        }
        log.info("POST /api/devassist/chat questionLength={}", question.trim().length());

        DeveloperAssistantService.HelpAnswer help = developerAssistantService.answerQuestion(question.trim());
        GitBranchInfo branchInfo = parseGitBranchJson(help.gitBranchJson());

        return new DevAssistChatResponse(
                question.trim(),
                help.answer(),
                help.model(),
                help.durationMs(),
                branchInfo.branch(),
                branchInfo.commit(),
                help.sources(),
                help.mcpToolCalls());
    }

    private GitBranchInfo loadGitBranch() {
        try {
            GitMcpFacade.ToolResult result = gitMcpFacade.getCurrentBranch();
            if (!result.ok()) {
                return GitBranchInfo.empty();
            }
            return parseGitBranchJson(result.text());
        } catch (Exception exception) {
            log.debug("Git branch status unavailable: {}", exception.getMessage());
            return GitBranchInfo.empty();
        }
    }

    private GitBranchInfo parseGitBranchJson(String json) {
        if (json == null || json.isBlank()) {
            return GitBranchInfo.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return new GitBranchInfo(
                    textOrNull(node, "branch"),
                    textOrNull(node, "shortCommit"),
                    textOrNull(node, "repoRoot"));
        } catch (Exception exception) {
            return new GitBranchInfo(null, null, null);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private record GitBranchInfo(String branch, String commit, String repoRoot) {
        static GitBranchInfo empty() {
            return new GitBranchInfo(null, null, null);
        }
    }
}

package com.example.llmchat.controller;

import com.example.llmchat.dto.SupportChatRequest;
import com.example.llmchat.dto.SupportChatResponse;
import com.example.llmchat.dto.SupportStatusResponse;
import com.example.llmchat.dto.SupportTicketStatusRequest;
import com.example.llmchat.mcp.McpConnectionService;
import com.example.llmchat.platform.PlatformServerService;
import com.example.llmchat.rag.RagCompletionService;
import com.example.llmchat.rag.SupportFaqIndexingService;
import com.example.llmchat.support.SupportAssistantService;
import com.example.llmchat.support.SupportTicketStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/support")
public class SupportController {

    private static final Logger log = LoggerFactory.getLogger(SupportController.class);

    private final SupportAssistantService supportAssistantService;
    private final SupportFaqIndexingService supportFaqIndexingService;
    private final SupportTicketStore supportTicketStore;
    private final McpConnectionService mcpConnectionService;
    private final PlatformServerService platformServerService;
    private final RagCompletionService ragCompletionService;
    private final String ticketsDir;

    public SupportController(
            SupportAssistantService supportAssistantService,
            SupportFaqIndexingService supportFaqIndexingService,
            SupportTicketStore supportTicketStore,
            McpConnectionService mcpConnectionService,
            PlatformServerService platformServerService,
            RagCompletionService ragCompletionService,
            @Value("${app.mcp.tickets-dir.absolute:}") String ticketsDir) {
        this.supportAssistantService = supportAssistantService;
        this.supportFaqIndexingService = supportFaqIndexingService;
        this.supportTicketStore = supportTicketStore;
        this.mcpConnectionService = mcpConnectionService;
        this.platformServerService = platformServerService;
        this.ragCompletionService = ragCompletionService;
        this.ticketsDir = ticketsDir;
    }

    @GetMapping("/status")
    public SupportStatusResponse status() {
        var mcpStatus = mcpConnectionService.getToolsSnapshot();
        var faqStatus = supportFaqIndexingService.status();
        boolean ticketsTool = mcpStatus.tools().stream()
                .anyMatch(tool -> "getTicket".equals(tool.name()) || "listTickets".equals(tool.name()));
        int ticketCount = 0;
        try {
            ticketCount = supportTicketStore.listSummaries().path("count").asInt(0);
        } catch (Exception exception) {
            log.debug("ticket count unavailable: {}", exception.getMessage());
        }
        return new SupportStatusResponse(
                platformServerService.info().version(),
                supportAssistantService.cloudConfigured(),
                ragCompletionService.cloudModel(),
                mcpStatus.connected(),
                ticketsTool,
                ticketCount,
                faqStatus.ready(),
                faqStatus.documentCount(),
                faqStatus.chunkCount(),
                faqStatus.indexPath(),
                ticketsDir != null && !ticketsDir.isBlank() ? ticketsDir : faqStatus.repoRoot() + "/support/tickets");
    }

    @GetMapping("/tickets")
    public Object listTickets() {
        // UTF-8 filesystem read for UI — MCP STDIO on Windows can mojibake Cyrillic.
        return supportTicketStore.listSummaries();
    }

    @PatchMapping("/tickets/{ticketId}/status")
    public Object updateTicketStatus(
            @PathVariable String ticketId,
            @RequestBody SupportTicketStatusRequest request) {
        String status = request != null ? request.status() : null;
        if (status == null || status.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status is required");
        }
        try {
            var result = supportTicketStore.updateStatus(ticketId, status.trim());
            if (!result.path("updated").asBoolean(false)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, result.path("message").asText("Transition not allowed"));
            }
            return result;
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
        }
    }

    @PostMapping("/chat")
    public SupportChatResponse chat(@RequestBody SupportChatRequest request) {
        String question = request != null ? request.question() : null;
        String ticketId = request != null ? request.ticketId() : null;
        if (question == null || question.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question is required");
        }
        log.info("POST /api/support/chat ticket={} qLen={}",
                ticketId, question.trim().length());
        try {
            SupportAssistantService.SupportAnswer answer =
                    supportAssistantService.answer(question.trim(), ticketId);
            return new SupportChatResponse(
                    question.trim(),
                    answer.ticketId(),
                    answer.answer(),
                    answer.model(),
                    answer.durationMs(),
                    answer.sources(),
                    answer.mcpToolCalls());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }
}

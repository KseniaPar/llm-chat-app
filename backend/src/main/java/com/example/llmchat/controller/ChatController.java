package com.example.llmchat.controller;

import com.example.llmchat.dto.ChatRequest;
import com.example.llmchat.dto.ChatResponse;
import com.example.llmchat.dto.LlmResult;
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
}

package com.example.llmchat.controller;

import com.example.llmchat.dto.PlatformInfoResponse;
import com.example.llmchat.dto.PlatformVerifyResponse;
import com.example.llmchat.platform.PlatformServerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform")
public class PlatformController {

    private static final Logger log = LoggerFactory.getLogger(PlatformController.class);

    private final PlatformServerService platformServerService;

    public PlatformController(PlatformServerService platformServerService) {
        this.platformServerService = platformServerService;
    }

    @GetMapping("/info")
    public PlatformInfoResponse info() {
        return platformServerService.info();
    }

    @PostMapping("/verify")
    public PlatformVerifyResponse verify() {
        log.info("POST /api/platform/verify");
        return platformServerService.verify();
    }
}

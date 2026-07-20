package com.example.llmchat.controller;

import com.example.llmchat.dto.PrReviewRequest;
import com.example.llmchat.dto.PrReviewResponse;
import com.example.llmchat.review.PrReviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/review")
public class ReviewController {

    private static final Logger log = LoggerFactory.getLogger(ReviewController.class);

    private final PrReviewService prReviewService;

    public ReviewController(PrReviewService prReviewService) {
        this.prReviewService = prReviewService;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ready", prReviewService.isReady());
        body.put("provider", "openrouter");
        body.put("message", prReviewService.isReady()
                ? "AI PR review готов"
                : "Задайте OPENROUTER_API_KEY");
        return body;
    }

    @PostMapping("/analyze")
    public PrReviewResponse analyze(@RequestBody(required = false) PrReviewRequest request) {
        log.info("POST /api/review/analyze titleLen={} diffLen={} files={}",
                request != null && request.title() != null ? request.title().length() : 0,
                request != null && request.diff() != null ? request.diff().length() : 0,
                request != null && request.changedFiles() != null ? request.changedFiles().size() : 0);
        try {
            return prReviewService.analyze(request != null ? request : new PrReviewRequest(null, null, null, null, null));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }
}

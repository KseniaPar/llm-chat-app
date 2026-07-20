package com.example.llmchat.dto;

import java.util.List;

/**
 * Request for PR / local code review.
 * If {@code diff} is blank, the service may collect {@code git diff} from the working tree.
 */
public record PrReviewRequest(
        String title,
        String diff,
        List<String> changedFiles,
        String baseRef,
        String headRef) {
}

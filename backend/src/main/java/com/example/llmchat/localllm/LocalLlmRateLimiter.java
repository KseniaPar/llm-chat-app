package com.example.llmchat.localllm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LocalLlmRateLimiter {

    private static final long WINDOW_MS = 60_000L;

    private final int limitPerMinute;
    private final ConcurrentHashMap<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    public LocalLlmRateLimiter(@Value("${app.local-llm.service.rate-limit-per-minute:20}") int limitPerMinute) {
        this.limitPerMinute = Math.max(1, limitPerMinute);
    }

    public int limitPerMinute() {
        return limitPerMinute;
    }

    public int acquire(String clientKey) {
        String key = clientKey == null || clientKey.isBlank() ? "anonymous" : clientKey.trim();
        long now = System.currentTimeMillis();
        Deque<Long> window = windows.computeIfAbsent(key, ignored -> new ArrayDeque<>());

        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MS) {
                window.pollFirst();
            }
            if (window.size() >= limitPerMinute) {
                long oldest = window.peekFirst();
                int retryAfterSeconds = (int) Math.max(1, (WINDOW_MS - (now - oldest) + 999) / 1000);
                throw new RateLimitExceededException(limitPerMinute, retryAfterSeconds);
            }
            window.addLast(now);
            return limitPerMinute - window.size();
        }
    }
}

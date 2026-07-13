package com.example.llmchat.localllm;

public class RateLimitExceededException extends RuntimeException {

    private final int limitPerMinute;
    private final int retryAfterSeconds;

    public RateLimitExceededException(int limitPerMinute, int retryAfterSeconds) {
        super("Превышен лимит запросов: " + limitPerMinute + " в минуту. Повторите через "
                + retryAfterSeconds + " с.");
        this.limitPerMinute = limitPerMinute;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int limitPerMinute() {
        return limitPerMinute;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

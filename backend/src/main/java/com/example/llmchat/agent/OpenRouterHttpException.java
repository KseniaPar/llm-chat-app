package com.example.llmchat.agent;

public class OpenRouterHttpException extends RuntimeException {

    private final int statusCode;

    public OpenRouterHttpException(int statusCode, String responseBody) {
        super(buildMessage(statusCode, responseBody));
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }

    private static String buildMessage(int statusCode, String responseBody) {
        String body = responseBody != null ? responseBody : "";
        return statusCode + " — " + body;
    }
}

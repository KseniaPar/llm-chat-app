package com.example.llmchat.localllm;

public class OllamaHttpException extends RuntimeException {

    private final int statusCode;

    public OllamaHttpException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}

package com.example.llmchat.dto;

public record CompareResponse(
        String unrestricted,
        String formatOnly,
        String lengthOnly,
        String stopOnly,
        String fullControl,
        String logs) {
}

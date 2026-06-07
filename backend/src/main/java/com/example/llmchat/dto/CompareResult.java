package com.example.llmchat.dto;

public record CompareResult(
        String unrestricted,
        String formatOnly,
        String lengthOnly,
        String stopOnly,
        String fullControl,
        String logs) {
}

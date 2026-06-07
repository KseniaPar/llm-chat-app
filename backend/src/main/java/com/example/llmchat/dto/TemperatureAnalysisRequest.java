package com.example.llmchat.dto;

public record TemperatureAnalysisRequest(
        String prompt,
        String temp0,
        String temp07,
        String temp12) {
}

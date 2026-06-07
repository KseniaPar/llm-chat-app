package com.example.llmchat.dto;

public record TemperatureCompareResult(
        String temp0,
        String temp07,
        String temp12,
        String comparison,
        String logs) {
}

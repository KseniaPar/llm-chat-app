package com.example.llmchat.dto;

import java.util.List;

public record ModelCallResponse(String response, ModelMetrics metrics, List<String> logs) {
}

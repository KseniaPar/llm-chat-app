package com.example.llmchat.dto;

import java.util.List;

public record LlmResult(String response, List<String> logs) {
}

package com.example.llmchat.dto;

import java.util.List;

public record ChatResponse(String response, List<String> logs) {
}

package com.example.llmchat.dto;

import java.util.List;

public record TaskTransitionsResponse(
        List<TaskTransitionDto> transitions) {
}

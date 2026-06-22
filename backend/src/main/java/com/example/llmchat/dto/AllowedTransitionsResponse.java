package com.example.llmchat.dto;

import com.example.llmchat.task.TaskTransitionType;

import java.util.List;

public record AllowedTransitionsResponse(
        List<String> allowed,
        List<String> allowedLabels) {

    public static AllowedTransitionsResponse from(List<TaskTransitionType> types) {
        return new AllowedTransitionsResponse(
                types.stream().map(TaskTransitionType::name).toList(),
                types.stream().map(TaskTransitionType::displayLabel).toList());
    }
}

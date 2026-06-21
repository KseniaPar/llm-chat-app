package com.example.llmchat.invariants;

import com.example.llmchat.personalization.UserProfile;
import com.example.llmchat.task.TaskState;

import java.util.Optional;

public record InvariantContext(
        String userId,
        String sessionId,
        String userMessage,
        Optional<TaskState> taskState,
        UserProfile profile) {
}

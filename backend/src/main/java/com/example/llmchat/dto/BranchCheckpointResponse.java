package com.example.llmchat.dto;

import java.util.List;

public record BranchCheckpointResponse(String sessionId, int forkMessageIndex) {
}

package com.example.llmchat.dto;

import java.util.List;

public record BranchCreateResponse(String sessionId, List<BranchInfoDto> branches, String activeBranchId) {
}

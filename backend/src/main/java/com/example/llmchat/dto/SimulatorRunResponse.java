package com.example.llmchat.dto;

import java.util.List;

public record SimulatorRunResponse(
        String sessionId,
        List<SimulatorTurnResponse> turns,
        boolean finished
) {
}

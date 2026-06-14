package com.example.llmchat.controller;

import com.example.llmchat.agent.SimulatorService;
import com.example.llmchat.dto.SimulatorNextResponse;
import com.example.llmchat.dto.SimulatorRunRequest;
import com.example.llmchat.dto.SimulatorRunResponse;
import com.example.llmchat.dto.SimulatorTurnRequest;
import com.example.llmchat.dto.SimulatorTurnResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/simulator")
public class SimulatorController {

    private final SimulatorService simulatorService;

    public SimulatorController(SimulatorService simulatorService) {
        this.simulatorService = simulatorService;
    }

    @PostMapping("/turn")
    public SimulatorTurnResponse turn(@RequestBody SimulatorTurnRequest request) {
        return simulatorService.runTurn(request);
    }

    @PostMapping("/next")
    public SimulatorNextResponse next(@RequestBody SimulatorTurnRequest request) {
        return simulatorService.generateNext(request);
    }

    @PostMapping("/run")
    public SimulatorRunResponse run(@RequestBody SimulatorRunRequest request) {
        return simulatorService.runConversation(request);
    }
}

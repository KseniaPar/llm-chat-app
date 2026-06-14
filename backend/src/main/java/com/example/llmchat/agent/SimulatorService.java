package com.example.llmchat.agent;

import com.example.llmchat.dto.AgentChatMessage;
import com.example.llmchat.dto.AgentRequest;
import com.example.llmchat.dto.AgentResponse;
import com.example.llmchat.dto.SimulatorNextResponse;
import com.example.llmchat.dto.SimulatorRunRequest;
import com.example.llmchat.dto.SimulatorRunResponse;
import com.example.llmchat.dto.SimulatorTurnRequest;
import com.example.llmchat.dto.SimulatorTurnResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class SimulatorService {

    private final UserSimulator userSimulator;
    private final ChatAgent chatAgent;
    private final ConversationStore conversationStore;
    private final int defaultMaxTurns;

    public SimulatorService(
            UserSimulator userSimulator,
            ChatAgent chatAgent,
            ConversationStore conversationStore,
            @Value("${app.simulator.default-max-turns}") int defaultMaxTurns) {
        this.userSimulator = userSimulator;
        this.chatAgent = chatAgent;
        this.conversationStore = conversationStore;
        this.defaultMaxTurns = defaultMaxTurns;
    }

    public SimulatorTurnResponse runTurn(SimulatorTurnRequest request) {
        String sessionId = resolveSessionId(request.sessionId());
        SimulatorMessage simulatorMessage = generateMessage(sessionId, request.goal());

        if (simulatorMessage.content().isBlank()) {
            return new SimulatorTurnResponse("", "", sessionId, simulatorMessage.finished());
        }

        AgentResponse agentResponse = chatAgent.run(
                new AgentRequest(simulatorMessage.content(), sessionId));

        return new SimulatorTurnResponse(
                simulatorMessage.content(),
                agentResponse.response(),
                agentResponse.sessionId(),
                simulatorMessage.finished());
    }

    public SimulatorNextResponse generateNext(SimulatorTurnRequest request) {
        String sessionId = resolveSessionId(request.sessionId());
        SimulatorMessage simulatorMessage = generateMessage(sessionId, request.goal());
        return new SimulatorNextResponse(simulatorMessage.content(), sessionId);
    }

    private SimulatorMessage generateMessage(String sessionId, String goal) {
        List<AgentChatMessage> history = conversationStore.getHistory(sessionId);
        return userSimulator.generateNextMessage(history, goal);
    }

    public SimulatorRunResponse runConversation(SimulatorRunRequest request) {
        int maxTurns = request.maxTurns() != null && request.maxTurns() > 0
                ? request.maxTurns()
                : defaultMaxTurns;

        String sessionId = null;
        List<SimulatorTurnResponse> turns = new ArrayList<>();
        boolean finished = false;

        for (int turn = 0; turn < maxTurns; turn++) {
            SimulatorTurnResponse turnResponse = runTurn(new SimulatorTurnRequest(sessionId, request.goal()));
            sessionId = turnResponse.sessionId();
            turns.add(turnResponse);

            if (turnResponse.finished()) {
                finished = true;
                break;
            }
        }

        return new SimulatorRunResponse(sessionId, List.copyOf(turns), finished);
    }

    private String resolveSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || !conversationStore.hasSession(sessionId)) {
            return conversationStore.createSession();
        }
        return sessionId;
    }
}

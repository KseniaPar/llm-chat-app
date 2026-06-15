package com.example.llmchat.agent;

import com.example.llmchat.dto.AgentChatMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ConversationPersistence {

    private static final Logger log = LoggerFactory.getLogger(ConversationPersistence.class);
    private static final TypeReference<List<AgentChatMessage>> LEGACY_SESSION_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final Path storagePath;

    public ConversationPersistence(
            ObjectMapper objectMapper,
            @Value("${app.agent.conversation-store-path}") String storagePath) {
        this.objectMapper = objectMapper;
        this.storagePath = Path.of(storagePath);
    }

    public Map<String, SessionState> load() {
        if (!Files.exists(storagePath)) {
            return new LinkedHashMap<>();
        }

        try {
            JsonNode root = objectMapper.readTree(storagePath.toFile());
            if (!root.isObject()) {
                return new LinkedHashMap<>();
            }

            Map<String, SessionState> sessions = new LinkedHashMap<>();
            root.fields().forEachRemaining(entry -> {
                SessionState state = parseSession(entry.getKey(), entry.getValue());
                if (state != null) {
                    sessions.put(entry.getKey(), state);
                }
            });
            return sessions;
        } catch (IOException exception) {
            log.warn("Не удалось загрузить историю диалогов из {}: {}", storagePath, exception.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private SessionState parseSession(String sessionId, JsonNode node) {
        try {
            if (node.isArray()) {
                List<AgentChatMessage> messages = objectMapper.convertValue(node, LEGACY_SESSION_TYPE);
                SessionState state = new SessionState();
                state.setMessages(new ArrayList<>(messages));
                state.setTotalMessageCount(messages.size());
                return state;
            }

            SessionState state = objectMapper.treeToValue(node, SessionState.class);
            if (state.getMessages() == null) {
                state.setMessages(List.of());
            }
            if (state.getTotalMessageCount() == 0 && state.getMessages() != null && !state.getMessages().isEmpty()) {
                state.setTotalMessageCount(state.getMessages().size());
            }
            return state;
        } catch (Exception exception) {
            log.warn("Не удалось разобрать сессию {}: {}", sessionId, exception.getMessage());
            return null;
        }
    }

    public void save(Map<String, SessionState> sessions) {
        try {
            Path parent = storagePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Map<String, SessionState> snapshot = new LinkedHashMap<>();
            for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
                SessionState copy = new SessionState();
                copy.setSummary(entry.getValue().getSummary());
                copy.setMessages(List.copyOf(entry.getValue().getMessages()));
                copy.setTotalMessageCount(entry.getValue().getTotalMessageCount());
                snapshot.put(entry.getKey(), copy);
            }

            Path tempPath = storagePath.resolveSibling(storagePath.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempPath.toFile(), snapshot);
            Files.move(tempPath, storagePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            log.error("Не удалось сохранить историю диалогов в {}: {}", storagePath, exception.getMessage());
        }
    }

    public void clearAll() {
        try {
            Files.deleteIfExists(storagePath);
        } catch (IOException exception) {
            log.warn("Не удалось удалить файл истории {}: {}", storagePath, exception.getMessage());
        }
    }
}

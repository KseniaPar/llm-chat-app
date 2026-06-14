package com.example.llmchat.agent;

import com.example.llmchat.dto.AgentChatMessage;
import com.fasterxml.jackson.core.type.TypeReference;
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
    private static final TypeReference<Map<String, List<AgentChatMessage>>> SESSIONS_TYPE =
            new TypeReference<>() {
            };

    private final ObjectMapper objectMapper;
    private final Path storagePath;

    public ConversationPersistence(
            ObjectMapper objectMapper,
            @Value("${app.agent.conversation-store-path}") String storagePath) {
        this.objectMapper = objectMapper;
        this.storagePath = Path.of(storagePath);
    }

    public Map<String, List<AgentChatMessage>> load() {
        if (!Files.exists(storagePath)) {
            return new LinkedHashMap<>();
        }

        try {
            Map<String, List<AgentChatMessage>> sessions = objectMapper.readValue(storagePath.toFile(), SESSIONS_TYPE);
            return new LinkedHashMap<>(sessions);
        } catch (IOException exception) {
            log.warn("Не удалось загрузить историю диалогов из {}: {}", storagePath, exception.getMessage());
            return new LinkedHashMap<>();
        }
    }

    public void save(Map<String, List<AgentChatMessage>> sessions) {
        try {
            Path parent = storagePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Map<String, List<AgentChatMessage>> snapshot = new LinkedHashMap<>();
            for (Map.Entry<String, List<AgentChatMessage>> entry : sessions.entrySet()) {
                snapshot.put(entry.getKey(), List.copyOf(entry.getValue()));
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

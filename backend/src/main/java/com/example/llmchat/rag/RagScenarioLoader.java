package com.example.llmchat.rag;

import com.example.llmchat.dto.RagScenarioDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Component
public class RagScenarioLoader {

    private final ObjectMapper objectMapper;
    private List<RagScenarioDto> cached;

    public RagScenarioLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<RagScenarioDto> loadAll() {
        if (cached != null) {
            return cached;
        }
        try (InputStream input = new ClassPathResource("rag/scenarios.json").getInputStream()) {
            cached = objectMapper.readValue(input, new TypeReference<>() {
            });
            return cached;
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось загрузить rag/scenarios.json", exception);
        }
    }

    public RagScenarioDto findById(String id) {
        return loadAll().stream()
                .filter(scenario -> scenario.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Сценарий не найден: " + id));
    }
}

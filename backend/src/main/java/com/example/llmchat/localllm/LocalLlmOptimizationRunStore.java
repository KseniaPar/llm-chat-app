package com.example.llmchat.localllm;

import com.example.llmchat.dto.LocalLlmOptimizationLastRunDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class LocalLlmOptimizationRunStore {

    private static final Logger log = LoggerFactory.getLogger(LocalLlmOptimizationRunStore.class);

    private final ObjectMapper objectMapper;
    private final Path storePath;

    public LocalLlmOptimizationRunStore(
            ObjectMapper objectMapper,
            @Value("${app.local-llm.optimization.last-run-path:data/local-llm-optimization-last-run.json}") String storePath) {
        this.objectMapper = objectMapper;
        this.storePath = Path.of(storePath);
    }

    public synchronized void save(LocalLlmOptimizationLastRunDto lastRun) {
        try {
            Path parent = storePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(storePath.toFile(), lastRun);
            log.info("Saved optimization last run to {}", storePath.toAbsolutePath());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to save optimization last run: " + exception.getMessage(), exception);
        }
    }

    public synchronized LocalLlmOptimizationLastRunDto load() {
        if (!Files.exists(storePath)) {
            return null;
        }
        try {
            return objectMapper.readValue(storePath.toFile(), LocalLlmOptimizationLastRunDto.class);
        } catch (IOException exception) {
            log.warn("Failed to read optimization last run from {}: {}", storePath, exception.getMessage());
            return null;
        }
    }
}

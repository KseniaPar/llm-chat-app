package com.example.llmchat.exam;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ExamJobStore {

    private static final Logger log = LoggerFactory.getLogger(ExamJobStore.class);

    private final Map<String, ExamJob> jobs = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final Path manifestPath;

    public ExamJobStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.manifestPath = Path.of("data", "exam-jobs.json");
        loadFromDisk();
    }

    public void save(ExamJob job) {
        jobs.put(job.id(), job);
        persist();
    }

    public Optional<ExamJob> find(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    public List<ExamJob> list() {
        return jobs.values().stream()
                .sorted(Comparator.comparing(ExamJob::createdAt).reversed())
                .toList();
    }

    private void loadFromDisk() {
        if (!Files.isRegularFile(manifestPath)) {
            return;
        }
        try {
            List<ExamJob> loaded = objectMapper.readValue(
                    manifestPath.toFile(), new TypeReference<List<ExamJob>>() {
                    });
            for (ExamJob job : loaded) {
                jobs.put(job.id(), job);
            }
            log.info("Loaded {} exam job(s) from {}", loaded.size(), manifestPath);
        } catch (IOException exception) {
            log.warn("Could not load exam jobs manifest: {}", exception.getMessage());
        }
    }

    private void persist() {
        try {
            Files.createDirectories(manifestPath.getParent());
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(manifestPath.toFile(), list());
        } catch (IOException exception) {
            log.warn("Could not persist exam jobs: {}", exception.getMessage());
        }
    }
}

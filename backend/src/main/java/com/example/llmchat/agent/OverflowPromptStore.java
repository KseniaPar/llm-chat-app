package com.example.llmchat.agent;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class OverflowPromptStore {

    private static final Logger log = LoggerFactory.getLogger(OverflowPromptStore.class);

    /** ~25k токенов на фрагмент — к 5-му вызову история переполняет окно 128k. */
    private static final int CHUNK_TARGET_CHARS = 90_000;

    private final Path promptPath;
    private final int overflowTurns;
    private final List<String> chunks = new ArrayList<>();

    public OverflowPromptStore(
            @Value("${app.token-demo.overflow-prompt}") String promptPath,
            @Value("${app.token-demo.overflow-turns:5}") int overflowTurns) {
        this.promptPath = resolvePromptPath(promptPath);
        this.overflowTurns = Math.max(1, overflowTurns);
    }

    private Path resolvePromptPath(String configuredPath) {
        Path path = Path.of(configuredPath);
        if (Files.exists(path)) {
            return path;
        }
        Path fromBackend = Path.of("backend").resolve(configuredPath);
        if (Files.exists(fromBackend)) {
            return fromBackend;
        }
        return path;
    }

    @PostConstruct
    void load() throws IOException {
        chunks.clear();
        if (!Files.exists(promptPath)) {
            throw new IllegalStateException("Файл overflow-промпта не найден: " + promptPath.toAbsolutePath());
        }

        String source = readPromptFile(promptPath);
        log.info("Загружен overflow-промпт: {} символов из {}", source.length(), promptPath.getFileName());

        int usableLength = Math.min(source.length(), CHUNK_TARGET_CHARS * overflowTurns);
        String usable = source.substring(0, usableLength);
        chunks.addAll(splitIntoChunks(usable, overflowTurns));
        log.info(
                "Overflow-сценарий: {} фрагментов, ~{} символов каждый (использовано {} из {})",
                chunks.size(),
                chunks.isEmpty() ? 0 : chunks.get(0).length(),
                usableLength,
                source.length());
    }

    private String readPromptFile(Path path) throws IOException {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (MalformedInputException exception) {
            return Files.readString(path, Charset.forName("Windows-1251"));
        }
    }

    private List<String> splitIntoChunks(String text, int parts) {
        if (text == null || text.isBlank()) {
            return List.of("");
        }
        if (parts <= 1) {
            return List.of(text);
        }

        int chunkSize = (int) Math.ceil((double) text.length() / parts);
        List<String> result = new ArrayList<>(parts);
        for (int index = 0; index < parts; index++) {
            int start = index * chunkSize;
            if (start >= text.length()) {
                break;
            }
            int end = Math.min(text.length(), start + chunkSize);
            result.add(text.substring(start, end));
        }
        return result;
    }

    public int totalCalls() {
        return chunks.size();
    }

    public String forCall(int callNumber, int totalCalls) {
        int index = callNumber - 1;
        if (index < 0 || index >= chunks.size()) {
            throw new IllegalArgumentException("Нет промпта для вызова " + callNumber);
        }

        return """
                Вызов %d из %d. Фрагмент «Метафизика» (%d/%d).
                Кратко ответь: о чём этот отрывок?

                %s
                """.formatted(callNumber, totalCalls, callNumber, totalCalls, chunks.get(index)).trim();
    }
}

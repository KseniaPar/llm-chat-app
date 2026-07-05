package com.example.llmchat.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

@Component
public class RagCorpusLoader {

    private static final Logger log = LoggerFactory.getLogger(RagCorpusLoader.class);

    private final String corpusDir;
    private final PdfTextExtractor pdfTextExtractor;

    public RagCorpusLoader(
            @Value("${app.rag.corpus-dir:data/rag-corpus}") String corpusDir,
            PdfTextExtractor pdfTextExtractor) {
        this.corpusDir = corpusDir;
        this.pdfTextExtractor = pdfTextExtractor;
    }

    public List<RagDocument> loadAll() {
        Path root = Paths.get(corpusDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            log.warn("RAG corpus directory not found: {}", root);
            return List.of();
        }
        List<RagDocument> documents = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(this::isSupported)
                    .sorted()
                    .forEach(path -> loadDocument(root, path).ifPresent(documents::add));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan corpus at " + root, exception);
        }
        log.info("Loaded {} documents from {}", documents.size(), root);
        return documents;
    }

    public CorpusStats stats() {
        List<RagDocument> docs = loadAll();
        int totalChars = docs.stream().mapToInt(d -> d.content().length()).sum();
        int estimatedPages = Math.max(1, totalChars / 3000);
        return new CorpusStats(docs.size(), totalChars, estimatedPages);
    }

    private boolean isSupported(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".md")
                || name.endsWith(".txt")
                || name.endsWith(".java")
                || name.endsWith(".pdf");
    }

    private java.util.Optional<RagDocument> loadDocument(Path root, Path path) {
        try {
            String relative = root.relativize(path).toString().replace('\\', '/');
            String title = path.getFileName().toString();
            String ext = extension(title);
            String content = ext.equals("pdf") ? pdfTextExtractor.extract(path) : Files.readString(path, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                log.warn("Empty content in {}", path);
                return java.util.Optional.empty();
            }
            log.info("Loaded {} — {} chars", title, content.length());
            return java.util.Optional.of(new RagDocument(relative, title, ext, content));
        } catch (Exception exception) {
            log.warn("Failed to read {}: {}", path, exception.getMessage());
            return java.util.Optional.empty();
        }
    }

    private String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "txt";
    }

    public record CorpusStats(int documentCount, int totalChars, int estimatedPages) {
    }
}

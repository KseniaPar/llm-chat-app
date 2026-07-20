package com.example.llmchat.review;

import com.example.llmchat.rag.ProjectDocsCorpusLoader;
import com.example.llmchat.rag.RagDocument;
import com.example.llmchat.rag.RagRetrievalMode;
import com.example.llmchat.rag.RagRetrievalService;
import com.example.llmchat.rag.RagStack;
import com.example.llmchat.rag.ChunkingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Builds size-capped context for PR review: project docs, optional RAG, changed-file excerpts, diff.
 */
@Component
public class PrReviewContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(PrReviewContextBuilder.class);

    static final int MAX_DIFF_CHARS = 28_000;
    static final int MAX_DOCS_CHARS = 14_000;
    static final int MAX_EXCERPT_CHARS = 2_400;
    static final int MAX_FILES_WITH_EXCERPTS = 10;
    static final int MAX_RAG_CHARS = 4_000;

    private final ProjectDocsCorpusLoader docsCorpusLoader;
    private final RagRetrievalService retrievalService;
    private final boolean useProjectRag;

    public PrReviewContextBuilder(
            ProjectDocsCorpusLoader docsCorpusLoader,
            RagRetrievalService retrievalService,
            @Value("${app.review.use-project-rag:true}") boolean useProjectRag) {
        this.docsCorpusLoader = docsCorpusLoader;
        this.retrievalService = retrievalService;
        this.useProjectRag = useProjectRag;
    }

    public Path repoRoot() {
        return docsCorpusLoader.repoRoot();
    }

    public BuiltContext build(String title, String diff, List<String> changedFiles) {
        List<String> sources = new ArrayList<>();
        String docs = loadDocs(sources);
        String rag = loadRagHints(title, diff, sources);
        String excerpts = loadExcerpts(changedFiles, sources);
        String cappedDiff = truncate(diff != null ? diff : "", MAX_DIFF_CHARS, "diff");

        return new BuiltContext(docs, rag, excerpts, cappedDiff, List.copyOf(sources));
    }

    public String collectWorkingTreeDiff() {
        Path root = repoRoot();
        try {
            return runGit(root, "diff", "HEAD");
        } catch (Exception exception) {
            log.warn("git diff HEAD failed: {}", exception.getMessage());
            return "";
        }
    }

    public List<String> collectChangedFiles(String diff) {
        if (diff == null || diff.isBlank()) {
            return List.of();
        }
        Set<String> files = new LinkedHashSet<>();
        for (String line : diff.split("\\R")) {
            if (line.startsWith("+++ b/") || line.startsWith("--- a/")) {
                String path = line.substring(6).trim();
                if (!"/dev/null".equals(path) && !path.isBlank()) {
                    files.add(path.replace('\\', '/'));
                }
            } else if (line.startsWith("diff --git ")) {
                // diff --git a/foo b/foo
                String[] parts = line.split("\\s+");
                if (parts.length >= 4) {
                    String b = parts[3];
                    if (b.startsWith("b/")) {
                        files.add(b.substring(2));
                    }
                }
            }
        }
        return List.copyOf(files);
    }

    public List<String> listChangedFilesVs(String baseRef) {
        Path root = repoRoot();
        String base = baseRef == null || baseRef.isBlank() ? "HEAD~1" : baseRef.trim();
        try {
            String output = runGit(root, "diff", "--name-only", base + "...HEAD");
            return output.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .toList();
        } catch (Exception exception) {
            log.warn("git diff --name-only failed: {}", exception.getMessage());
            return List.of();
        }
    }

    public String collectDiffVs(String baseRef) {
        Path root = repoRoot();
        String base = baseRef == null || baseRef.isBlank() ? "HEAD~1" : baseRef.trim();
        try {
            return runGit(root, "diff", base + "...HEAD");
        } catch (Exception exception) {
            log.warn("git diff {}...HEAD failed: {}", base, exception.getMessage());
            return collectWorkingTreeDiff();
        }
    }

    private String loadDocs(List<String> sources) {
        StringBuilder builder = new StringBuilder();
        try {
            List<RagDocument> docs = docsCorpusLoader.loadAll();
            for (RagDocument doc : docs) {
                String chunk = "## " + doc.sourcePath() + "\n" + doc.content() + "\n\n";
                if (builder.length() + chunk.length() > MAX_DOCS_CHARS) {
                    builder.append("… [docs truncated]\n");
                    break;
                }
                builder.append(chunk);
                sources.add(doc.sourcePath());
            }
        } catch (Exception exception) {
            log.warn("Failed to load project docs: {}", exception.getMessage());
            return "(project docs unavailable)";
        }
        return builder.isEmpty() ? "(no project docs)" : builder.toString().trim();
    }

    private String loadRagHints(String title, String diff, List<String> sources) {
        if (!useProjectRag) {
            return "";
        }
        String query = (title != null && !title.isBlank() ? title + "\n" : "")
                + (diff != null ? diff.substring(0, Math.min(diff.length(), 800)) : "");
        if (query.isBlank()) {
            return "";
        }
        try {
            RagRetrievalService.RetrievalResult retrieval = retrievalService.retrieve(
                    query,
                    ChunkingStrategy.STRUCTURE,
                    RagRetrievalMode.RAW,
                    4,
                    0.30,
                    RagStack.PROJECT);
            if (retrieval.chunks().isEmpty()) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            int i = 1;
            for (RagRetrievalService.ScoredChunk scored : retrieval.chunks()) {
                String block = "[" + i++ + "] " + scored.source()
                        + " (score=" + String.format(Locale.ROOT, "%.2f", scored.score()) + ")\n"
                        + scored.content() + "\n\n";
                if (builder.length() + block.length() > MAX_RAG_CHARS) {
                    break;
                }
                builder.append(block);
                sources.add("RAG:" + scored.source());
            }
            return builder.toString().trim();
        } catch (Exception exception) {
            log.debug("PROJECT RAG for review skipped: {}", exception.getMessage());
            return "";
        }
    }

    private String loadExcerpts(List<String> changedFiles, List<String> sources) {
        if (changedFiles == null || changedFiles.isEmpty()) {
            return "(no changed files listed)";
        }
        Path root = repoRoot();
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (String relative : changedFiles) {
            if (count >= MAX_FILES_WITH_EXCERPTS) {
                builder.append("… [more files omitted]\n");
                break;
            }
            if (relative == null || relative.isBlank()) {
                continue;
            }
            String normalized = relative.replace('\\', '/');
            if (shouldSkipExcerpt(normalized)) {
                continue;
            }
            Path file = root.resolve(normalized).normalize();
            if (!file.startsWith(root.normalize()) || !Files.isRegularFile(file)) {
                continue;
            }
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                String excerpt = truncate(content, MAX_EXCERPT_CHARS, normalized);
                builder.append("### ").append(normalized).append("\n```\n")
                        .append(excerpt).append("\n```\n\n");
                sources.add(normalized);
                count++;
            } catch (Exception exception) {
                log.debug("Skip excerpt {}: {}", normalized, exception.getMessage());
            }
        }
        return builder.isEmpty() ? "(could not read changed files)" : builder.toString().trim();
    }

    private static boolean shouldSkipExcerpt(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".gif")
                || lower.endsWith(".webp")
                || lower.endsWith(".ico")
                || lower.endsWith(".jar")
                || lower.endsWith(".class")
                || lower.endsWith(".map")
                || lower.contains("/target/")
                || lower.contains("/node_modules/")
                || lower.contains("/.git/");
    }

    private static String truncate(String text, int max, String label) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "\n… [" + label + " truncated at " + max + " chars]";
    }

    private static String runGit(Path repoRoot, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repoRoot.toString());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("git timed out: " + String.join(" ", args));
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("git failed (" + process.exitValue() + "): "
                    + output.lines().limit(5).collect(Collectors.joining(" | ")));
        }
        return output;
    }

    public record BuiltContext(
            String docs,
            String ragHints,
            String fileExcerpts,
            String diff,
            List<String> sources) {
    }
}

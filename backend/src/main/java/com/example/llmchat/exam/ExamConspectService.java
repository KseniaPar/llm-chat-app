package com.example.llmchat.exam;

import com.example.llmchat.agent.OpenRouterHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Service
public class ExamConspectService {

    private static final Logger log = LoggerFactory.getLogger(ExamConspectService.class);
    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private final ExamJobStore jobStore;
    private final OpenRouterHttpClient openRouterHttpClient;
    private final String openRouterModel;
    private final Path notesDir;

    public ExamConspectService(
            ExamJobStore jobStore,
            OpenRouterHttpClient openRouterHttpClient,
            @Value("${app.openrouter.model}") String openRouterModel,
            @Value("${app.exam.notes-dir:data/exam-notes}") String notesDir) {
        this.jobStore = jobStore;
        this.openRouterHttpClient = openRouterHttpClient;
        this.openRouterModel = openRouterModel;
        this.notesDir = Path.of(notesDir);
    }

    public ExamConspectResult generateAndSave(ExamJob job, String cleanMd) throws IOException {
        String conspect = expandConspect(job.title(), cleanMd);
        Files.createDirectories(notesDir);
        String filename = sanitize(job.title()) + "-" + job.id() + ".md";
        Path out = notesDir.resolve(filename);
        Files.writeString(out, conspect, StandardCharsets.UTF_8);
        jobStore.save(job.withNotesPath(out.toString()));
        log.info("Exam conspect saved: {}", out);
        return new ExamConspectResult(job.id(), out.toString(), conspect);
    }

    public ExamConspectResult read(String jobId) throws IOException {
        ExamJob job = jobStore.find(jobId)
                .orElseThrow(() -> new IllegalArgumentException("job not found: " + jobId));
        if (job.notesPath() == null || job.notesPath().isBlank()) {
            throw new IllegalStateException("Конспект ещё не готов — дождитесь окончания обработки.");
        }
        Path path = Path.of(job.notesPath());
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Файл конспекта не найден: " + path);
        }
        String markdown = Files.readString(path, StandardCharsets.UTF_8);
        return new ExamConspectResult(jobId, path.toString(), markdown);
    }

    public ExamConspectResult generate(String jobId) throws IOException {
        ExamJob job = jobStore.find(jobId)
                .orElseThrow(() -> new IllegalArgumentException("job not found: " + jobId));
        if (job.notesPath() != null && Files.isRegularFile(Path.of(job.notesPath()))) {
            return read(jobId);
        }
        if (job.status() != ExamJobStatus.READY && job.status() != ExamJobStatus.CONSPECT) {
            throw new IllegalStateException("Лекция ещё не готова: " + job.status());
        }
        String cleanMd = job.cleanPath() != null
                ? Files.readString(Path.of(job.cleanPath()), StandardCharsets.UTF_8)
                : buildFromSegments(job);
        return generateAndSave(job, cleanMd);
    }

    private String expandConspect(String title, String cleanMd) {
        String prompt = """
                Сделай экзаменационный конспект по лекции «%s» на русском.
                Формат markdown:
                # %s
                ## Ключевые определения
                ## Основные тезисы (5–8 пунктов)
                ## Вопросы к экзамену (5 вопросов)
                ## Цитаты с таймкодами (3–5 штук, формат «[@ mm:ss] …»)
                Опирайся только на текст ниже.

                --- МАТЕРИАЛ ---
                %s
                """.formatted(title, title, truncate(cleanMd, 12000));
        try {
            var result = openRouterHttpClient.complete(
                    openRouterModel,
                    0.2,
                    2000,
                    List.of(new OpenRouterHttpClient.ChatMessage("user", prompt)));
            if (result.content() != null && !result.content().isBlank()) {
                return result.content().trim();
            }
        } catch (Exception exception) {
            log.warn("Conspect LLM failed: {}", exception.getMessage());
        }
        return "# " + title + "\n\n" + cleanMd;
    }

    private static String buildFromSegments(ExamJob job) {
        StringBuilder md = new StringBuilder("# ").append(job.title()).append("\n\n");
        for (ExamTranscriptSegment segment : job.segments()) {
            md.append("- ").append(segment.text())
                    .append(" [@ ").append(segment.timestampLabel()).append("]\n");
        }
        return md.toString();
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "\n\n…";
    }

    private static String sanitize(String title) {
        String safe = title.replaceAll("[^a-zA-Z0-9а-яА-Я\\-_]", "-");
        return safe.length() > 40 ? safe.substring(0, 40) : safe;
    }

    public record ExamConspectResult(String jobId, String path, String markdown) {
    }
}

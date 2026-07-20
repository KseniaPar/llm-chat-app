package com.example.llmchat.exam;

import com.example.llmchat.agent.OpenRouterHttpClient;
import com.example.llmchat.agent.OpenRouterTranscriptionClient;
import com.example.llmchat.rag.ChunkingStrategy;
import com.example.llmchat.rag.RagChunk;
import com.example.llmchat.rag.RagDocument;
import com.example.llmchat.rag.RagIndexRepository;
import com.example.llmchat.rag.RagIndexStore;
import com.example.llmchat.rag.RagStack;
import com.example.llmchat.rag.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class ExamPipelineService {

    private static final Logger log = LoggerFactory.getLogger(ExamPipelineService.class);

    private final OpenRouterTranscriptionClient transcriptionClient;
    private final OpenRouterHttpClient openRouterHttpClient;
    private final ExamJobStore jobStore;
    private final ExamAudioChunker audioChunker;
    private final ExamConspectService conspectService;
    private final RagIndexStore indexStore;
    private final EmbeddingService embeddingService;
    private final String openRouterModel;
    private final Path audioDir;
    private final Path transcriptDir;
    private final long maxUploadBytes;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public ExamPipelineService(
            OpenRouterTranscriptionClient transcriptionClient,
            OpenRouterHttpClient openRouterHttpClient,
            ExamJobStore jobStore,
            ExamAudioChunker audioChunker,
            ExamConspectService conspectService,
            RagIndexStore indexStore,
            EmbeddingService embeddingService,
            @Value("${app.openrouter.model}") String openRouterModel,
            @Value("${app.exam.audio-dir:data/exam-audio}") String audioDir,
            @Value("${app.exam.transcript-dir:data/exam-transcripts}") String transcriptDir,
            @Value("${app.exam.max-upload-bytes:104857600}") long maxUploadBytes) {
        this.transcriptionClient = transcriptionClient;
        this.openRouterHttpClient = openRouterHttpClient;
        this.jobStore = jobStore;
        this.audioChunker = audioChunker;
        this.conspectService = conspectService;
        this.indexStore = indexStore;
        this.embeddingService = embeddingService;
        this.openRouterModel = openRouterModel;
        this.audioDir = Path.of(audioDir);
        this.transcriptDir = Path.of(transcriptDir);
        this.maxUploadBytes = maxUploadBytes;
    }

    public boolean cloudConfigured() {
        return transcriptionClient.configured();
    }

    public ExamJob enqueueUpload(MultipartFile file, String title, String subject) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required");
        }
        if (file.getSize() > maxUploadBytes) {
            throw new IllegalArgumentException("Файл слишком большой — для демо используйте короткий фрагмент лекции (< "
                    + (maxUploadBytes / 1024 / 1024) + " МБ).");
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "lecture.mp3";
        String safeName = original.replaceAll("[^a-zA-Z0-9._\\-а-яА-Я ]", "_");
        String lectureTitle = title != null && !title.isBlank()
                ? title.trim()
                : safeName.replaceFirst("\\.[^.]+$", "");
        String lectureSubject = subject != null ? subject.trim() : "";

        Files.createDirectories(audioDir);
        Files.createDirectories(transcriptDir);
        Path audioPath = audioDir.resolve(id + "_" + safeName).toAbsolutePath();
        Files.createDirectories(audioPath.getParent());
        file.transferTo(audioPath);

        ExamJob job = new ExamJob(
                id,
                lectureTitle,
                lectureSubject,
                original,
                audioPath.toString(),
                null,
                null,
                null,
                ExamJobStatus.QUEUED,
                "В очереди",
                null,
                0,
                List.of(),
                Instant.now(),
                Instant.now());
        jobStore.save(job);
        executor.submit(() -> processJob(id));
        return job;
    }

    public ExamJob retryJob(String id) {
        ExamJob job = jobStore.find(id)
                .orElseThrow(() -> new IllegalArgumentException("job not found: " + id));
        if (job.audioPath() == null || !Files.isRegularFile(Path.of(job.audioPath()))) {
            throw new IllegalStateException("Исходный аудиофайл не найден — загрузите лекцию снова.");
        }
        ExamJob queued = job.withStatus(ExamJobStatus.QUEUED, "Повторная транскрипция…");
        jobStore.save(queued);
        executor.submit(() -> processJob(id));
        return queued;
    }

    private void processJob(String id) {
        ExamJob job = jobStore.find(id).orElse(null);
        if (job == null) {
            return;
        }
        try {
            job = job.withStatus(ExamJobStatus.TRANSCRIBING, "Подготовка аудио…");
            jobStore.save(job);

            Path audioPath = Path.of(job.audioPath());
            Path jobDir = transcriptDir.resolve(id);
            Files.createDirectories(jobDir);
            Path chunksDir = jobDir.resolve("chunks");

            ExamAudioChunker.SplitResult split =
                    audioChunker.splitForTranscription(audioPath, chunksDir);
            List<Path> parts = split.parts();

            List<ExamTranscriptSegment> segments = new ArrayList<>();
            String language = "ru";
            double totalDuration = 0;
            double timeOffset = 0;
            StringBuilder fullText = new StringBuilder();
            int segIndex = 0;

            for (int partNum = 0; partNum < parts.size(); partNum++) {
                Path part = parts.get(partNum);
                String progress = parts.size() == 1
                        ? "Транскрипция через OpenRouter Whisper…"
                        : "Транскрипция часть " + (partNum + 1) + "/" + parts.size() + "…";
                job = job.withStatus(ExamJobStatus.TRANSCRIBING, progress);
                jobStore.save(job);

                OpenRouterTranscriptionClient.TranscriptionResult partResult =
                        transcriptionClient.transcribeFile(part, "ru");
                if (partResult.language() != null && !partResult.language().isBlank()) {
                    language = partResult.language();
                }
                double partDuration = partResult.durationSec() > 0
                        ? partResult.durationSec()
                        : audioChunker.probeDuration(part);

                for (OpenRouterTranscriptionClient.TranscriptSegment segment : partResult.segments()) {
                    segments.add(new ExamTranscriptSegment(
                            segIndex++,
                            timeOffset + segment.startSec(),
                            timeOffset + segment.endSec(),
                            segment.text()));
                }
                if (!partResult.text().isBlank()) {
                    if (!fullText.isEmpty()) {
                        fullText.append('\n');
                    }
                    fullText.append(partResult.text().trim());
                }
                timeOffset += partDuration;
                totalDuration += partDuration;
            }

            Path transcriptJsonl = jobDir.resolve("transcript.jsonl");
            writeJsonl(transcriptJsonl, segments);
            Path rawText = jobDir.resolve("raw.txt");
            Files.writeString(rawText, fullText.toString(), StandardCharsets.UTF_8);

            job = job.withProgress(
                    ExamJobStatus.CLEANING,
                    split.chunked()
                            ? "Сборка транскрипта из " + parts.size() + " частей…"
                            : "Очистка и структурирование конспекта…",
                    language,
                    totalDuration,
                    segments,
                    transcriptJsonl.toString(),
                    null);
            jobStore.save(job);

            String cleanMd = cleanTranscript(job.title(), segments, fullText.toString());
            Path cleanPath = jobDir.resolve("clean.md");
            Files.writeString(cleanPath, cleanMd, StandardCharsets.UTF_8);

            job = job.withProgress(
                    ExamJobStatus.INDEXING,
                    "Индексация для RAG…",
                    language,
                    totalDuration,
                    segments,
                    transcriptJsonl.toString(),
                    cleanPath.toString());
            jobStore.save(job);

            indexLecture(job, cleanMd, segments);

            job = job.withProgress(
                    ExamJobStatus.CONSPECT,
                    "Генерация конспекта лекции…",
                    language,
                    totalDuration,
                    segments,
                    transcriptJsonl.toString(),
                    cleanPath.toString());
            jobStore.save(job);

            conspectService.generateAndSave(job, cleanMd);
            job = jobStore.find(id).orElse(job);

            job = job.withProgress(
                    ExamJobStatus.READY,
                    "Готово — конспект и RAG по всем лекциям.",
                    language,
                    totalDuration,
                    segments,
                    transcriptJsonl.toString(),
                    cleanPath.toString());
            jobStore.save(job);
            log.info("Exam job {} ready: {} segment(s)", id, segments.size());
        } catch (Exception exception) {
            log.warn("Exam job {} failed: {}", id, exception.getMessage());
            jobStore.find(id).ifPresent(failed ->
                    jobStore.save(failed.withStatus(ExamJobStatus.FAILED, exception.getMessage())));
        }
    }

    private String cleanTranscript(String title, List<ExamTranscriptSegment> segments, String rawText) {
        if (!transcriptionClient.configured()) {
            return buildFallbackClean(title, segments);
        }
        StringBuilder snippet = new StringBuilder();
        for (ExamTranscriptSegment segment : segments.stream().limit(40).toList()) {
            snippet.append('[').append(segment.timestampLabel()).append("] ")
                    .append(segment.text()).append('\n');
        }
        if (segments.size() > 40) {
            snippet.append("\n… ещё ").append(segments.size() - 40).append(" сегментов");
        }
        String prompt = """
                Ты редактор учебных лекций по религиоведению.
                На входе — сырой транскрипт лекции «%s» с таймкодами.
                Сделай чистый markdown-конспект на русском:
                • убери «ээ», повторы, оговорки;
                • сохрани смысл и термины;
                • добавь 3–6 заголовков ## по ходу лекции;
                • после каждого абзаца укажи таймкод в формате [@ mm:ss].
                Не выдумывай факты вне текста.

                --- ТРАНСКРИПТ ---
                %s
                """.formatted(title, snippet);
        try {
            var result = openRouterHttpClient.complete(
                    openRouterModel,
                    0.2,
                    1800,
                    List.of(new OpenRouterHttpClient.ChatMessage("user", prompt)));
            if (result.content() != null && !result.content().isBlank()) {
                return result.content().trim();
            }
        } catch (Exception exception) {
            log.warn("Clean transcript LLM failed: {}", exception.getMessage());
        }
        return buildFallbackClean(title, segments);
    }

    private static String buildFallbackClean(String title, List<ExamTranscriptSegment> segments) {
        StringBuilder md = new StringBuilder("# ").append(title).append("\n\n");
        for (ExamTranscriptSegment segment : segments) {
            md.append(segment.text()).append(" [@ ")
                    .append(segment.timestampLabel()).append("]\n\n");
        }
        return md.toString().trim();
    }

    private void indexLecture(ExamJob job, String cleanMd, List<ExamTranscriptSegment> segments) {
        RagIndexRepository repo = indexStore.exam();
        ChunkingStrategy strategy = ChunkingStrategy.STRUCTURE;
        String sourceKey = "lecture:" + job.id();

        RagDocument cleanDoc = new RagDocument(
                sourceKey + "/clean.md",
                job.title(),
                "exam-lecture",
                cleanMd);
        long cleanDocId = repo.upsertDocument(cleanDoc);
        List<String> cleanTexts = List.of(cleanMd);
        List<float[]> cleanEmb = embeddingService.embedBatch(cleanTexts, RagStack.EXAM);
        RagChunk cleanChunk = new RagChunk(
                sourceKey + "#clean",
                job.title(),
                job.title(),
                "конспект",
                cleanMd,
                0,
                cleanMd.length(),
                cleanMd.length() / 4);
        repo.insertChunk(cleanDocId, strategy, cleanChunk, cleanEmb.get(0));

        long segmentDocId = repo.upsertDocument(new RagDocument(
                sourceKey + "/segments",
                job.title(),
                "exam-segments",
                job.title() + " segments"));
        List<String> segmentTexts = new ArrayList<>();
        List<RagChunk> segmentChunks = new ArrayList<>();
        for (ExamTranscriptSegment segment : segments) {
            String section = "@" + ExamTimeFormat.atTimestamp(segment.startSec());
            String content = segment.text();
            segmentTexts.add(content);
            segmentChunks.add(new RagChunk(
                    sourceKey + "#seg-" + segment.index(),
                    job.title(),
                    job.title(),
                    section,
                    content,
                    0,
                    content.length(),
                    content.length() / 4));
        }
        if (!segmentTexts.isEmpty()) {
            List<float[]> segmentEmb = embeddingService.embedBatch(segmentTexts, RagStack.EXAM);
            for (int i = 0; i < segmentChunks.size(); i++) {
                repo.insertChunk(segmentDocId, strategy, segmentChunks.get(i), segmentEmb.get(i));
            }
        }
        repo.recordIndexRun(strategy, segmentChunks.size() + 1, 200, 50, 2000);
    }

    private static void writeJsonl(Path path, List<ExamTranscriptSegment> segments) throws IOException {
        StringBuilder jsonl = new StringBuilder();
        for (ExamTranscriptSegment segment : segments) {
            jsonl.append(String.format(Locale.ROOT,
                    "{\"index\":%d,\"start\":%.2f,\"end\":%.2f,\"text\":%s}%n",
                    segment.index(),
                    segment.startSec(),
                    segment.endSec(),
                    objectMapperQuote(segment.text())));
        }
        Files.writeString(path, jsonl.toString(), StandardCharsets.UTF_8);
    }

    private static String objectMapperQuote(String text) {
        if (text == null) {
            return "\"\"";
        }
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static String extension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "mp3";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}

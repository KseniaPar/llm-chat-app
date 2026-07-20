package com.example.llmchat.exam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Splits long lecture audio into smaller MP3 parts for STT (ffmpeg).
 */
@Component
public class ExamAudioChunker {

    private static final Logger log = LoggerFactory.getLogger(ExamAudioChunker.class);

    private final String ffmpegCommand;
    private final String ffprobeCommand;
    private final long chunkMaxBytes;
    private final int chunkSeconds;

    public ExamAudioChunker(
            @Value("${app.exam.ffmpeg-path:ffmpeg}") String ffmpegPath,
            @Value("${app.exam.ffprobe-path:ffprobe}") String ffprobePath,
            @Value("${app.exam.chunk-max-bytes:15728640}") long chunkMaxBytes,
            @Value("${app.exam.chunk-seconds:600}") int chunkSeconds) {
        this.ffmpegCommand = ffmpegPath;
        this.ffprobeCommand = ffprobePath;
        this.chunkMaxBytes = chunkMaxBytes;
        this.chunkSeconds = Math.max(120, chunkSeconds);
    }

    public record SplitResult(List<Path> parts, boolean chunked) {
    }

    public SplitResult splitForTranscription(Path input, Path workDir) throws IOException, InterruptedException {
        if (!Files.isRegularFile(input)) {
            throw new IllegalArgumentException("Audio file not found: " + input);
        }
        long sizeBytes = Files.size(input);
        double durationSec = probeDuration(input);
        if (sizeBytes <= chunkMaxBytes && durationSec <= chunkSeconds + 30) {
            return new SplitResult(List.of(input), false);
        }
        Files.createDirectories(workDir);
        Path outputPattern = workDir.resolve("part_%03d.mp3");
        int segmentTime = chooseSegmentTime(sizeBytes, durationSec);
        log.info("Splitting audio {} ({} MB, {}s) into ~{}s chunks",
                input.getFileName(),
                String.format(Locale.ROOT, "%.1f", sizeBytes / 1024.0 / 1024.0),
                String.format(Locale.ROOT, "%.0f", durationSec),
                segmentTime);

        runProcess(List.of(
                ffmpegCommand,
                "-hide_banner",
                "-loglevel", "error",
                "-y",
                "-i", input.toAbsolutePath().toString(),
                "-f", "segment",
                "-segment_time", String.valueOf(segmentTime),
                "-reset_timestamps", "1",
                "-ac", "1",
                "-ar", "16000",
                "-b:a", "64k",
                outputPattern.toAbsolutePath().toString()), 10, TimeUnit.MINUTES);

        List<Path> parts;
        try (Stream<Path> stream = Files.list(workDir)) {
            parts = stream
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.startsWith("part_") && name.endsWith(".mp3");
                    })
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        if (parts.isEmpty()) {
            throw new IllegalStateException("ffmpeg не создал части — проверьте ffmpeg в PATH");
        }
        log.info("Audio split into {} part(s)", parts.size());
        return new SplitResult(parts, true);
    }

    public double probeDuration(Path audioPath) throws IOException, InterruptedException {
        String output = runProcess(List.of(
                ffprobeCommand,
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                audioPath.toAbsolutePath().toString()), 30, TimeUnit.SECONDS);
        String line = output.lines()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .findFirst()
                .orElse("");
        if (line.isBlank()) {
            return chunkSeconds;
        }
        try {
            return Double.parseDouble(line);
        } catch (NumberFormatException exception) {
            log.warn("Could not parse ffprobe duration '{}': {}", line, exception.getMessage());
            return chunkSeconds;
        }
    }

    private int chooseSegmentTime(long sizeBytes, double durationSec) {
        if (durationSec <= 0) {
            return chunkSeconds;
        }
        double bytesPerSecond = sizeBytes / durationSec;
        long targetBytes = Math.max(5L * 1024 * 1024, chunkMaxBytes / 2);
        int bySize = (int) Math.max(120, Math.min(chunkSeconds, targetBytes / Math.max(1, bytesPerSecond)));
        return Math.min(bySize, chunkSeconds);
    }

    private static String runProcess(List<String> command, long timeout, TimeUnit unit)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        if (!process.waitFor(timeout, unit)) {
            process.destroyForcibly();
            throw new IllegalStateException("Timeout: " + String.join(" ", command));
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(
                    "Command failed (" + process.exitValue() + "): "
                            + String.join(" ", command) + "\n" + output);
        }
        return output.toString();
    }
}

package com.example.llmchat.exam;

import com.example.llmchat.rag.ChunkingStrategy;
import com.example.llmchat.rag.RagRetrievalMode;
import com.example.llmchat.rag.RagRetrievalService;
import com.example.llmchat.rag.RagStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class ExamLectureTools {

    private static final Logger log = LoggerFactory.getLogger(ExamLectureTools.class);

    public static final String TOOL_SERVER_NAME = "exam-lecture";

    private static final ThreadLocal<List<String>> SOURCES =
            ThreadLocal.withInitial(CopyOnWriteArrayList::new);
    private static final ThreadLocal<List<ExamCitation>> CITATIONS =
            ThreadLocal.withInitial(CopyOnWriteArrayList::new);

    private final RagRetrievalService retrievalService;

    public ExamLectureTools(RagRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    public static void beginRecording() {
        SOURCES.get().clear();
        CITATIONS.get().clear();
    }

    public static List<String> drainSources() {
        List<String> copy = List.copyOf(SOURCES.get());
        SOURCES.get().clear();
        return copy;
    }

    public static List<ExamCitation> drainCitations() {
        List<ExamCitation> copy = List.copyOf(CITATIONS.get());
        CITATIONS.get().clear();
        return copy;
    }

    @Tool(description = """
            Search all indexed lecture transcripts (exam corpus) with timestamped segments.
            Use for exam questions — cite lecture title and @ mm:ss from any matching lecture.""")
    public String retrieveExamLecture(
            @ToolParam(description = "Exam question or keywords in Russian") String query) {
        String q = query != null ? query.trim() : "";
        if (q.isBlank()) {
            return "(empty query)";
        }
        try {
            RagRetrievalService.RetrievalResult retrieval = retrievalService.retrieve(
                    q,
                    ChunkingStrategy.STRUCTURE,
                    RagRetrievalMode.RAW,
                    12,
                    0.22,
                    RagStack.EXAM);
            if (retrieval.chunks().isEmpty()) {
                return "(нет фрагментов — загрузите аудио лекции на /exam.html)";
            }
            StringBuilder context = new StringBuilder();
            int index = 1;
            for (RagRetrievalService.ScoredChunk scored : retrieval.chunks()) {
                String timeLabel = scored.section() != null && scored.section().startsWith("@")
                        ? scored.section()
                        : "@ ??:??";
                String sourceLine = scored.title() + " " + timeLabel
                        + " (score=" + String.format(Locale.ROOT, "%.2f", scored.score()) + ")";
                SOURCES.get().add(sourceLine);
                CITATIONS.get().add(new ExamCitation(
                        scored.title(),
                        timeLabel,
                        truncate(scored.content(), 280),
                        scored.score()));
                context.append('[').append(index++).append("] ")
                        .append(scored.title()).append(' ').append(timeLabel).append('\n')
                        .append(scored.content()).append("\n\n");
            }
            log.info("retrieveExamLecture query='{}' -> {} chunk(s)", q, retrieval.chunks().size());
            return context.toString().trim();
        } catch (Exception exception) {
            log.warn("retrieveExamLecture failed: {}", exception.getMessage());
            return "(exam RAG error: " + exception.getMessage() + ")";
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    public record ExamCitation(String lecture, String timestamp, String quote, double score) {
    }
}

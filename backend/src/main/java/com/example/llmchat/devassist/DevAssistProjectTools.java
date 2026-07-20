package com.example.llmchat.devassist;

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

/**
 * Spring AI tools for the developer assistant: project docs via {@link RagStack#PROJECT}.
 */
@Component
public class DevAssistProjectTools {

    private static final Logger log = LoggerFactory.getLogger(DevAssistProjectTools.class);

    public static final String TOOL_SERVER_NAME = "devassist-rag";

    private static final ThreadLocal<List<String>> SOURCES =
            ThreadLocal.withInitial(CopyOnWriteArrayList::new);

    private final RagRetrievalService retrievalService;

    public DevAssistProjectTools(RagRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    public static void beginSourceRecording() {
        SOURCES.get().clear();
    }

    public static List<String> drainSources() {
        List<String> copy = List.copyOf(SOURCES.get());
        SOURCES.get().clear();
        return copy;
    }

    @Tool(description = """
            Search the project documentation corpus (README.md and project/docs) via RAG.
            Use for questions about architecture, modules, REST API, data schema, MCP, or where code lives.
            Pass a short focused query in Russian or English.""")
    public String retrieveProjectDocs(
            @ToolParam(description = "Search query about the llm-chat-app project") String query) {
        String q = query != null ? query.trim() : "";
        if (q.isBlank()) {
            return "(empty query)";
        }
        try {
            RagRetrievalService.RetrievalResult retrieval = retrievalService.retrieve(
                    q,
                    ChunkingStrategy.STRUCTURE,
                    RagRetrievalMode.RAW,
                    6,
                    0.35,
                    RagStack.PROJECT);
            if (retrieval.chunks().isEmpty()) {
                return "(no matching documentation chunks — index may be empty; try POST /api/rag/project/index)";
            }
            StringBuilder context = new StringBuilder();
            int index = 1;
            for (RagRetrievalService.ScoredChunk scored : retrieval.chunks()) {
                String source = scored.source()
                        + " (score=" + String.format(Locale.ROOT, "%.2f", scored.score()) + ")";
                SOURCES.get().add(source);
                context.append('[').append(index++).append("] ").append(scored.source());
                if (scored.section() != null && !scored.section().isBlank()) {
                    context.append(" § ").append(scored.section());
                }
                context.append('\n').append(scored.content()).append("\n\n");
            }
            log.info("retrieveProjectDocs query='{}' -> {} chunk(s)", q, retrieval.chunks().size());
            return context.toString().trim();
        } catch (Exception exception) {
            log.warn("retrieveProjectDocs failed: {}", exception.getMessage());
            return "(RAG error: " + exception.getMessage() + ")";
        }
    }
}

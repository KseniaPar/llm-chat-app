package com.example.llmchat.support;

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
public class SupportFaqTools {

    private static final Logger log = LoggerFactory.getLogger(SupportFaqTools.class);

    public static final String TOOL_SERVER_NAME = "support-faq";

    private static final ThreadLocal<List<String>> SOURCES =
            ThreadLocal.withInitial(CopyOnWriteArrayList::new);

    private final RagRetrievalService retrievalService;

    public SupportFaqTools(RagRetrievalService retrievalService) {
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
            Search the product support FAQ corpus (support/faq) via RAG.
            Use for questions about login/auth, RAG index, MCP, deploy, and product surfaces.""")
    public String retrieveSupportFaq(
            @ToolParam(description = "User question or search query in Russian or English") String query) {
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
                    0.30,
                    RagStack.SUPPORT);
            if (retrieval.chunks().isEmpty()) {
                return "(no FAQ chunks — run POST /api/rag/support/index)";
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
            log.info("retrieveSupportFaq query='{}' -> {} chunk(s)", q, retrieval.chunks().size());
            return context.toString().trim();
        } catch (Exception exception) {
            log.warn("retrieveSupportFaq failed: {}", exception.getMessage());
            return "(FAQ RAG error: " + exception.getMessage() + ")";
        }
    }
}

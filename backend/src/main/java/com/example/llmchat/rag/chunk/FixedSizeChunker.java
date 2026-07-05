package com.example.llmchat.rag.chunk;

import com.example.llmchat.rag.RagChunk;
import com.example.llmchat.rag.RagDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class FixedSizeChunker {

    public List<RagChunk> chunk(RagDocument document, int chunkSize, int overlap) {
        String text = document.content();
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<RagChunk> chunks = new ArrayList<>();
        int index = 0;
        int chunkNum = 0;
        while (index < text.length()) {
            int end = Math.min(index + chunkSize, text.length());
            if (end < text.length()) {
                int breakAt = findBreakPoint(text, index, end);
                if (breakAt > index) {
                    end = breakAt;
                }
            }
            String slice = text.substring(index, end).trim();
            if (!slice.isEmpty()) {
                chunkNum++;
                String chunkId = document.title() + "#fixed-" + chunkNum;
                chunks.add(new RagChunk(
                        chunkId,
                        document.sourcePath(),
                        document.title(),
                        "chunk-" + chunkNum,
                        slice,
                        index,
                        end,
                        estimateTokens(slice)));
            }
            if (end >= text.length()) {
                break;
            }
            index = Math.max(index + 1, end - overlap);
        }
        return chunks;
    }

    private int findBreakPoint(String text, int start, int end) {
        int searchFrom = Math.max(start, end - 200);
        int paragraph = text.lastIndexOf("\n\n", end - 1);
        if (paragraph >= searchFrom) {
            return paragraph + 2;
        }
        int newline = text.lastIndexOf('\n', end - 1);
        if (newline >= searchFrom) {
            return newline + 1;
        }
        int space = text.lastIndexOf(' ', end - 1);
        if (space >= searchFrom) {
            return space + 1;
        }
        return end;
    }

    static int estimateTokens(String text) {
        return Math.max(1, text.length() / 4);
    }
}

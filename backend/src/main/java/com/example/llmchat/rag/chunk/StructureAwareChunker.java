package com.example.llmchat.rag.chunk;

import com.example.llmchat.rag.RagChunk;
import com.example.llmchat.rag.RagDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class StructureAwareChunker {

    private static final Pattern MARKDOWN_HEADER = Pattern.compile("^(#{1,3})\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern PDF_SECTION = Pattern.compile(
            "(?m)^(?:"
                    + "(?:Глава|ГЛАВА|Часть|ЧАСТЬ|Раздел|РАЗДЕЛ|Отдел|ОТДЕЛ|Параграф|ПАРАГРАФ)"
                    + "\\s+[\\dIVXLCА-ЯЁ]+(?:[.:].*)?"
                    + "|\\d+(?:\\.\\d+)*\\.\\s+[А-ЯA-Z].{3,80}"
                    + ")\\s*$");
    private static final Pattern JAVA_CLASS = Pattern.compile(
            "^\\s*(public\\s+)?(class|interface|enum|record)\\s+(\\w+)",
            Pattern.MULTILINE);
    private static final Pattern JAVA_METHOD = Pattern.compile(
            "^\\s*(public|private|protected|static|\\s)+[\\w<>,\\[\\]\\s]+\\s+(\\w+)\\s*\\([^)]*\\)\\s*\\{",
            Pattern.MULTILINE);

    private final int maxSectionChars;

    public StructureAwareChunker(@Value("${app.rag.fixed-chunk-size:1200}") int maxSectionChars) {
        this.maxSectionChars = maxSectionChars;
    }

    public List<RagChunk> chunk(RagDocument document) {
        String text = document.content();
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String docType = document.docType().toLowerCase(Locale.ROOT);
        if (docType.equals("java")) {
            return chunkJava(document, text);
        }
        if (docType.equals("md") || docType.equals("markdown")) {
            return chunkMarkdown(document, text);
        }
        if (docType.equals("pdf")) {
            return chunkPdf(document, text);
        }
        return chunkByFile(document, text);
    }

    private List<RagChunk> chunkPdf(RagDocument document, String text) {
        List<Section> sections = new ArrayList<>();
        Matcher matcher = PDF_SECTION.matcher(text);
        int lastStart = 0;
        String lastTitle = document.title();
        while (matcher.find()) {
            if (matcher.start() > lastStart) {
                sections.add(new Section(lastTitle, lastStart, matcher.start()));
            }
            lastTitle = matcher.group().trim();
            lastStart = matcher.start();
        }
        sections.add(new Section(lastTitle, lastStart, text.length()));

        if (sections.size() <= 1) {
            return splitLargeText(document, text, document.title());
        }

        List<RagChunk> chunks = new ArrayList<>();
        for (Section section : sections) {
            String slice = text.substring(section.start(), section.end()).trim();
            if (slice.length() <= maxSectionChars * 2) {
                chunks.addAll(toChunks(document, text, List.of(section)));
            } else {
                chunks.addAll(splitLargeText(document, slice, section.title()));
            }
        }
        return chunks;
    }

    private List<RagChunk> splitLargeText(RagDocument document, String text, String sectionTitle) {
        List<RagChunk> chunks = new ArrayList<>();
        int overlap = Math.min(200, maxSectionChars / 6);
        int index = 0;
        int chunkNum = 0;
        while (index < text.length()) {
            int end = Math.min(index + maxSectionChars, text.length());
            if (end < text.length()) {
                int breakAt = text.lastIndexOf("\n\n", end);
                if (breakAt > index + maxSectionChars / 2) {
                    end = breakAt + 2;
                }
            }
            String slice = text.substring(index, end).trim();
            if (!slice.isEmpty()) {
                chunkNum++;
                chunks.add(new RagChunk(
                        document.title() + "#" + sanitize(sectionTitle) + "-" + chunkNum,
                        document.sourcePath(),
                        document.title(),
                        sectionTitle,
                        slice,
                        index,
                        end,
                        FixedSizeChunker.estimateTokens(slice)));
            }
            if (end >= text.length()) {
                break;
            }
            index = Math.max(index + 1, end - overlap);
        }
        return chunks;
    }

    private List<RagChunk> chunkMarkdown(RagDocument document, String text) {
        List<Section> sections = new ArrayList<>();
        Matcher matcher = MARKDOWN_HEADER.matcher(text);
        int lastStart = 0;
        String lastTitle = document.title();
        while (matcher.find()) {
            if (matcher.start() > lastStart) {
                sections.add(new Section(lastTitle, lastStart, matcher.start()));
            }
            lastTitle = matcher.group(2).trim();
            lastStart = matcher.start();
        }
        sections.add(new Section(lastTitle, lastStart, text.length()));
        return toChunks(document, text, sections);
    }

    private List<RagChunk> chunkJava(RagDocument document, String text) {
        List<Section> sections = new ArrayList<>();
        Matcher classMatcher = JAVA_CLASS.matcher(text);
        int lastStart = 0;
        String lastTitle = document.title();
        while (classMatcher.find()) {
            if (classMatcher.start() > lastStart) {
                sections.add(new Section(lastTitle, lastStart, classMatcher.start()));
            }
            lastTitle = classMatcher.group(3);
            lastStart = classMatcher.start();
        }
        if (sections.isEmpty()) {
            Matcher methodMatcher = JAVA_METHOD.matcher(text);
            lastStart = 0;
            lastTitle = "methods";
            while (methodMatcher.find()) {
                if (methodMatcher.start() > lastStart) {
                    sections.add(new Section(lastTitle, lastStart, methodMatcher.start()));
                }
                lastTitle = methodMatcher.group(2);
                lastStart = methodMatcher.start();
            }
        }
        sections.add(new Section(lastTitle, lastStart, text.length()));
        return toChunks(document, text, sections);
    }

    private List<RagChunk> chunkByFile(RagDocument document, String text) {
        if (text.length() > maxSectionChars * 2) {
            return splitLargeText(document, text, document.title());
        }
        return List.of(new RagChunk(
                document.title() + "#full",
                document.sourcePath(),
                document.title(),
                document.title(),
                text.trim(),
                0,
                text.length(),
                FixedSizeChunker.estimateTokens(text)));
    }

    private List<RagChunk> toChunks(RagDocument document, String text, List<Section> sections) {
        List<RagChunk> chunks = new ArrayList<>();
        int chunkNum = 0;
        for (Section section : sections) {
            String slice = text.substring(section.start(), section.end()).trim();
            if (slice.length() < 50 && sections.size() > 1) {
                continue;
            }
            if (slice.isEmpty()) {
                continue;
            }
            chunkNum++;
            chunks.add(new RagChunk(
                    document.title() + "#" + sanitize(section.title()) + "-" + chunkNum,
                    document.sourcePath(),
                    document.title(),
                    section.title(),
                    slice,
                    section.start(),
                    section.end(),
                    FixedSizeChunker.estimateTokens(slice)));
        }
        return chunks;
    }

    private static String sanitize(String title) {
        return title.replaceAll("[^a-zA-Z0-9\\-_.\\u0400-\\u04FF ]", "-");
    }

    private record Section(String title, int start, int end) {
    }
}

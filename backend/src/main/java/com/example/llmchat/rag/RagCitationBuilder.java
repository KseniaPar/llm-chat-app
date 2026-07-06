package com.example.llmchat.rag;

import com.example.llmchat.dto.RagQuoteDto;
import com.example.llmchat.dto.RagSourceDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RagCitationBuilder {

    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?…])\\s+|\\n{2,}");
    private static final int MAX_QUOTE_CHARS = 420;
    private static final int MIN_QUOTE_CHARS = 40;
    private static final int MAX_QUOTES = 3;

    public record CitationBundle(List<RagSourceDto> sources, List<RagQuoteDto> quotes) {
    }

    public record QuoteExtraction(String text, int rank, double semanticScore) {
    }

    public CitationBundle build(String originalQuery, String searchQuery, List<RagRetrievalService.ScoredChunk> chunks) {
        List<String> keywords = RagKeywords.merge(originalQuery, searchQuery);
        Map<String, RagSourceDto> sourcesById = new LinkedHashMap<>();
        List<RagQuoteDto> quotes = new ArrayList<>();

        List<QuoteCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            RagRetrievalService.ScoredChunk chunk = chunks.get(i);
            String passage = extractPassage(chunk.content(), keywords);
            if (passage.isBlank() || !passageMatchesQuery(passage, keywords)) {
                continue;
            }
            int keywordHits = RagKeywords.countMatches(passage, keywords);
            candidates.add(new QuoteCandidate(chunk, passage, i + 1, keywordHits));
        }

        candidates.sort(Comparator
                .comparingInt((QuoteCandidate c) -> primaryKeywordScore(c.passage(), keywords)).reversed()
                .thenComparingInt(QuoteCandidate::keywordHits).reversed()
                .thenComparingDouble(c -> c.chunk().semanticScore()).reversed());

        List<String> seenPassages = new ArrayList<>();
        Set<String> seenSections = new HashSet<>();
        int quoteRank = 1;
        for (QuoteCandidate candidate : candidates) {
            if (isDuplicatePassage(candidate.passage(), seenPassages)) {
                continue;
            }
            String sectionKey = candidate.chunk().section() != null
                    ? candidate.chunk().section().trim().toLowerCase(Locale.ROOT)
                    : candidate.chunk().chunkId();
            if (seenSections.contains(sectionKey)) {
                continue;
            }
            seenPassages.add(passageFingerprint(candidate.passage()));
            seenSections.add(sectionKey);

            RagRetrievalService.ScoredChunk chunk = candidate.chunk();
            sourcesById.putIfAbsent(
                    chunk.chunkId(),
                    new RagSourceDto(chunk.source(), chunk.section(), chunk.chunkId()));
            quotes.add(new RagQuoteDto(
                    chunk.chunkId(),
                    chunk.source(),
                    chunk.section(),
                    quoteRank++,
                    candidate.passage(),
                    chunk.semanticScore(),
                    chunk.score()));
            if (quotes.size() >= MAX_QUOTES) {
                break;
            }
        }

        return new CitationBundle(List.copyOf(sourcesById.values()), List.copyOf(quotes));
    }

    public QuoteExtraction bestPassage(String originalQuery, String searchQuery, RagRetrievalService.ScoredChunk chunk, int rank) {
        List<String> keywords = RagKeywords.merge(originalQuery, searchQuery);
        String passage = extractPassage(chunk.content(), keywords);
        return new QuoteExtraction(passage, rank, chunk.semanticScore());
    }

    private record QuoteCandidate(
            RagRetrievalService.ScoredChunk chunk,
            String passage,
            int rank,
            int keywordHits) {
    }

    private static String extractPassage(String content, List<String> keywords) {
        if (content == null || content.isBlank()) {
            return "";
        }

        List<SentenceSpan> sentences = splitSentences(content);
        if (sentences.isEmpty()) {
            return trimPassage(content.substring(0, Math.min(content.length(), MAX_QUOTE_CHARS)), content);
        }

        int bestIdx = 0;
        int bestScore = -1;
        for (int i = 0; i < sentences.size(); i++) {
            int score = scoreSentence(sentences.get(i).text(), keywords);
            if (score > bestScore) {
                bestScore = score;
                bestIdx = i;
            }
        }

        if (bestScore <= 0 && !keywords.isEmpty()) {
            return extractAroundKeyword(content, keywords);
        }

        int startIdx = bestIdx;
        int endIdx = bestIdx;
        int length = spanLength(sentences, startIdx, endIdx);

        while (length < MAX_QUOTE_CHARS) {
            boolean expanded = false;
            if (endIdx + 1 < sentences.size()) {
                int nextScore = scoreSentence(sentences.get(endIdx + 1).text(), keywords);
                if (nextScore > 0 || length < MIN_QUOTE_CHARS) {
                    endIdx++;
                    length = spanLength(sentences, startIdx, endIdx);
                    expanded = true;
                }
            }
            if (startIdx > 0 && length < MAX_QUOTE_CHARS) {
                int prevScore = scoreSentence(sentences.get(startIdx - 1).text(), keywords);
                if (prevScore > 0) {
                    startIdx--;
                    length = spanLength(sentences, startIdx, endIdx);
                    expanded = true;
                }
            }
            if (!expanded) {
                break;
            }
        }

        int from = sentences.get(startIdx).start();
        int to = sentences.get(endIdx).end();
        return trimPassage(content.substring(from, Math.min(to, from + MAX_QUOTE_CHARS)), content);
    }

    private static String extractAroundKeyword(String content, List<String> keywords) {
        String lower = content.toLowerCase(Locale.ROOT);
        int bestPos = -1;
        int bestLen = 0;
        for (String keyword : keywords) {
            if (keyword.length() < 4) {
                continue;
            }
            int pos = lower.indexOf(keyword);
            if (pos >= 0 && keyword.length() >= bestLen) {
                bestPos = pos;
                bestLen = keyword.length();
            }
        }
        if (bestPos < 0) {
            return "";
        }

        int sentStart = findSentenceStart(content, bestPos);
        int sentEnd = findSentenceEnd(content, bestPos);
        int to = Math.min(content.length(), Math.max(sentEnd, sentStart + MIN_QUOTE_CHARS));
        to = Math.min(to, sentStart + MAX_QUOTE_CHARS);
        to = extendToSentenceEnd(content, to);
        return trimPassage(content.substring(sentStart, to), content);
    }

    private static int findSentenceStart(String content, int position) {
        int start = Math.max(0, position - MAX_QUOTE_CHARS / 2);
        Matcher matcher = Pattern.compile("[.!?…]\\s+|\\n{2,}").matcher(content.substring(0, position));
        int last = start;
        while (matcher.find()) {
            if (matcher.end() <= position) {
                last = matcher.end();
            }
        }
        return last;
    }

    private static int findSentenceEnd(String content, int position) {
        Matcher matcher = SENTENCE_BOUNDARY.matcher(content.substring(position));
        if (matcher.find()) {
            return position + matcher.start() + 1;
        }
        return Math.min(content.length(), position + MAX_QUOTE_CHARS);
    }

    private static int extendToSentenceEnd(String content, int to) {
        Matcher matcher = SENTENCE_BOUNDARY.matcher(content.substring(Math.min(to, content.length())));
        if (matcher.find() && matcher.start() > 0 && matcher.start() < 120) {
            return Math.min(content.length(), to + matcher.start() + 1);
        }
        return Math.min(content.length(), to);
    }

    private static String trimPassage(String passage, String fullContent) {
        String trimmed = passage.strip();
        if (trimmed.length() < MIN_QUOTE_CHARS && fullContent.length() > trimmed.length()) {
            int end = Math.min(fullContent.length(), trimmed.length() + (MIN_QUOTE_CHARS - trimmed.length()) + 40);
            trimmed = fullContent.substring(0, end).strip();
        }
        if (trimmed.length() > MAX_QUOTE_CHARS) {
            trimmed = trimmed.substring(0, MAX_QUOTE_CHARS).strip();
            if (!trimmed.endsWith("…")) {
                trimmed = trimmed + "…";
            }
        }
        return trimmed;
    }

    private static int scoreSentence(String sentence, List<String> keywords) {
        if (sentence == null || sentence.isBlank()) {
            return 0;
        }
        String lower = sentence.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String keyword : keywords) {
            if (keyword.length() >= 4 && lower.contains(keyword)) {
                score += keyword.length() >= 7 ? 3 : 1;
            }
        }
        return score;
    }

    private static int spanLength(List<SentenceSpan> sentences, int startIdx, int endIdx) {
        return sentences.get(endIdx).end() - sentences.get(startIdx).start();
    }

    private static List<SentenceSpan> splitSentences(String content) {
        List<SentenceSpan> spans = new ArrayList<>();
        Matcher matcher = SENTENCE_BOUNDARY.matcher(content);
        int cursor = 0;
        while (matcher.find()) {
            addSentenceSpan(spans, content, cursor, matcher.start());
            cursor = matcher.end();
        }
        addSentenceSpan(spans, content, cursor, content.length());
        return spans.stream().filter(span -> span.text().length() >= 12).toList();
    }

    private static void addSentenceSpan(List<SentenceSpan> spans, String content, int start, int end) {
        if (end <= start) {
            return;
        }
        String text = content.substring(start, end).strip();
        if (text.length() < 12) {
            return;
        }
        int trimStart = content.substring(start, end).indexOf(text.charAt(0));
        spans.add(new SentenceSpan(start + Math.max(trimStart, 0), start + Math.max(trimStart, 0) + text.length(), text));
    }

    private record SentenceSpan(int start, int end, String text) {
    }

    private static boolean passageMatchesQuery(String passage, List<String> keywords) {
        if (keywords.isEmpty()) {
            return true;
        }
        String lower = passage.toLowerCase(Locale.ROOT);
        List<String> strong = keywords.stream().filter(keyword -> keyword.length() >= 6).toList();
        if (!strong.isEmpty()) {
            return strong.stream().anyMatch(lower::contains);
        }
        return keywords.stream().anyMatch(lower::contains);
    }

    private static int primaryKeywordScore(String passage, List<String> keywords) {
        String lower = passage.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String keyword : keywords) {
            if (lower.contains(keyword)) {
                score += keyword.length() * 10;
            }
        }
        return score;
    }

    private static String passageFingerprint(String passage) {
        return passage.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static boolean isDuplicatePassage(String passage, List<String> seen) {
        String fingerprint = passageFingerprint(passage);
        if (fingerprint.length() < 40) {
            return false;
        }
        String prefix = fingerprint.substring(0, Math.min(100, fingerprint.length()));
        for (String other : seen) {
            if (other.equals(fingerprint)) {
                return true;
            }
            if (other.length() >= 40 && (other.contains(prefix) || fingerprint.contains(other.substring(0, Math.min(100, other.length()))))) {
                return true;
            }
        }
        return false;
    }
}

package com.example.llmchat.rag;

import com.example.llmchat.dto.RagEvalQuestionDto;
import com.example.llmchat.dto.RagEvalValidationResultDto;
import com.example.llmchat.dto.RagQuoteDto;
import com.example.llmchat.dto.RagQueryResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class RagEvalValidator {

    public RagEvalValidationResultDto validate(RagEvalQuestionDto question, RagQueryResponse response) {
        boolean hasSources = response.sources() != null && !response.sources().isEmpty();
        boolean hasQuotes = response.quotes() != null && !response.quotes().isEmpty();
        boolean quotesValid = validateQuotesInChunks(response);
        boolean meaningAligned = validateMeaning(question, response);
        boolean passed = hasSources
                && hasQuotes
                && quotesValid
                && meaningAligned
                && !RagConfidence.UNKNOWN.name().equals(response.confidence());

        return new RagEvalValidationResultDto(
                question,
                response,
                hasSources,
                hasQuotes,
                quotesValid,
                meaningAligned,
                passed);
    }

    private boolean validateQuotesInChunks(RagQueryResponse response) {
        if (response.quotes() == null || response.quotes().isEmpty()) {
            return false;
        }
        Map<String, RagQueryResponse.ChunkUsedDto> chunksById = response.chunksUsed().stream()
                .collect(Collectors.toMap(RagQueryResponse.ChunkUsedDto::chunkId, Function.identity(), (a, b) -> a));

        for (RagQuoteDto quote : response.quotes()) {
            RagQueryResponse.ChunkUsedDto chunk = chunksById.get(quote.chunkId());
            if (chunk == null) {
                return false;
            }
            String haystack = normalize(chunk.preview());
            String needle = normalize(quote.text()).replace("…", "").trim();
            if (needle.length() < 20) {
                continue;
            }
            int checkLen = Math.min(needle.length(), Math.max(40, needle.length()));
            if (!haystack.contains(needle.substring(0, Math.min(checkLen, needle.length())))) {
                return false;
            }
        }
        return true;
    }

    private boolean validateMeaning(RagEvalQuestionDto question, RagQueryResponse response) {
        if (RagConfidence.UNKNOWN.name().equals(response.confidence())) {
            return false;
        }
        String answer = normalize(response.answer());
        if (answer.contains("не знаю") || answer.contains("нет достаточной информации")) {
            return false;
        }

        int matched = 0;
        int required = 0;
        for (String token : question.expectedSources()) {
            String normalized = normalize(token);
            if (normalized.length() < 4) {
                continue;
            }
            required++;
            if (answer.contains(normalized)) {
                matched++;
                continue;
            }
            boolean inQuote = response.quotes().stream()
                    .anyMatch(q -> normalize(q.text()).contains(normalized));
            if (inQuote) {
                matched++;
            }
        }
        if (required == 0) {
            return answer.length() > 30;
        }
        return matched >= Math.max(1, required / 2);
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    public List<String> summarizeFailures(RagEvalValidationResultDto result) {
        List<String> failures = new ArrayList<>();
        if (!result.hasSources()) {
            failures.add("нет sources");
        }
        if (!result.hasQuotes()) {
            failures.add("нет quotes");
        }
        if (!result.quotesValid()) {
            failures.add("цитаты не из чанков");
        }
        if (!result.meaningAligned()) {
            failures.add("смысл не совпадает");
        }
        if (RagConfidence.UNKNOWN.name().equals(result.response().confidence())) {
            failures.add("confidence=UNKNOWN");
        }
        return failures;
    }
}

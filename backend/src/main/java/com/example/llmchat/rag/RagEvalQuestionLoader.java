package com.example.llmchat.rag;

import com.example.llmchat.dto.RagEvalQuestionDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

@Component
public class RagEvalQuestionLoader {

    private static final String RESOURCE = "rag/eval-questions.json";

    private final ObjectMapper objectMapper;
    private List<RagEvalQuestionDto> cached;

    public RagEvalQuestionLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<RagEvalQuestionDto> loadAll() {
        if (cached != null) {
            return cached;
        }
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
            cached = objectMapper.readValue(input, new TypeReference<>() {
            });
            return cached;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to load eval questions from " + RESOURCE, exception);
        }
    }
}

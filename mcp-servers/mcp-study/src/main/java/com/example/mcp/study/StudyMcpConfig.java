package com.example.mcp.study;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StudyMcpConfig {

    @Bean
    ToolCallbackProvider studyTools(StudyReferenceTools studyReferenceTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(studyReferenceTools)
                .build();
    }
}

package com.example.mcp.files;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FilesMcpConfig {

    @Bean
    ToolCallbackProvider fileTools(ProjectFileTools projectFileTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(projectFileTools)
                .build();
    }
}

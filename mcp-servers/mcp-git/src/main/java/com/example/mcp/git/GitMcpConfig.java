package com.example.mcp.git;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GitMcpConfig {

    @Bean
    ToolCallbackProvider gitTools(GitProjectTools gitProjectTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(gitProjectTools)
                .build();
    }
}

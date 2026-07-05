package com.example.mcp.pipeline;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PipelineMcpConfig {

    @Bean
    ToolCallbackProvider pipelineToolCallbacks(PipelineTools pipelineTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(pipelineTools)
                .build();
    }
}

package com.example.mcp.scheduler;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SchedulerMcpConfig {

    @Bean
    ToolCallbackProvider schedulerToolCallbacks(SchedulerTools schedulerTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(schedulerTools)
                .build();
    }
}

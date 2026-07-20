package com.example.mcp.tickets;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TicketsMcpConfig {

    @Bean
    ToolCallbackProvider ticketsTools(TicketTools ticketTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(ticketTools)
                .build();
    }
}

package com.example.mcp.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class PipelineMcpStartupListener {

    private static final Logger log = LoggerFactory.getLogger(PipelineMcpStartupListener.class);

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("mcp-pipeline STDIO server ready — tools: search, summarize, saveToFile");
    }
}

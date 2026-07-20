package com.example.mcp.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class GitMcpStartupListener {

    private static final Logger log = LoggerFactory.getLogger(GitMcpStartupListener.class);

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("mcp-git STDIO server ready — tools: getCurrentBranch, listRepoFiles, getWorkingTreeDiff");
    }
}

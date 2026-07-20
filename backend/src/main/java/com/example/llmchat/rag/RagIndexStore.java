package com.example.llmchat.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RagIndexStore {

    private final RagIndexRepository local;
    private final RagIndexRepository cloud;
    private final RagIndexRepository project;

    public RagIndexStore(
            @Value("${app.rag.local.index-db:data/rag-index-local.db}") String localIndexDb,
            @Value("${app.rag.index-db:data/rag-index.db}") String cloudIndexDb,
            @Value("${app.rag.project-index-db:data/rag-project-index.db}") String projectIndexDb) {
        this.local = new RagIndexRepository(localIndexDb);
        this.cloud = new RagIndexRepository(cloudIndexDb);
        this.project = new RagIndexRepository(projectIndexDb);
    }

    public RagIndexRepository forStack(RagStack stack) {
        return switch (stack) {
            case LOCAL -> local;
            case CLOUD -> cloud;
            case PROJECT -> project;
        };
    }

    public RagIndexRepository local() {
        return local;
    }

    public RagIndexRepository cloud() {
        return cloud;
    }

    public RagIndexRepository project() {
        return project;
    }

    public String localPath() {
        return local.dbPath();
    }

    public String cloudPath() {
        return cloud.dbPath();
    }

    public String projectPath() {
        return project.dbPath();
    }
}

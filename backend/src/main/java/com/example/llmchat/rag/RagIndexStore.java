package com.example.llmchat.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RagIndexStore {

    private final RagIndexRepository local;
    private final RagIndexRepository cloud;

    public RagIndexStore(
            @Value("${app.rag.local.index-db:data/rag-index-local.db}") String localIndexDb,
            @Value("${app.rag.index-db:data/rag-index.db}") String cloudIndexDb) {
        this.local = new RagIndexRepository(localIndexDb);
        this.cloud = new RagIndexRepository(cloudIndexDb);
    }

    public RagIndexRepository forStack(RagStack stack) {
        return stack == RagStack.LOCAL ? local : cloud;
    }

    public RagIndexRepository local() {
        return local;
    }

    public RagIndexRepository cloud() {
        return cloud;
    }

    public String localPath() {
        return local.dbPath();
    }

    public String cloudPath() {
        return cloud.dbPath();
    }
}

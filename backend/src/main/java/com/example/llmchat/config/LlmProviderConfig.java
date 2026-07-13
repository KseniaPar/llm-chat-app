package com.example.llmchat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LlmProviderConfig {

    private final boolean local;

    public LlmProviderConfig(@Value("${app.llm.provider:local}") String provider) {
        this.local = !"cloud".equalsIgnoreCase(provider);
    }

    public boolean isLocal() {
        return local;
    }
}

package com.example.llmchat.rag;

public enum RagStack {
    LOCAL,
    CLOUD,
    /** Project documentation (README + project/docs) — uses local embeddings. */
    PROJECT,
    /** Support FAQ corpus (support/faq) — uses local embeddings. */
    SUPPORT
}

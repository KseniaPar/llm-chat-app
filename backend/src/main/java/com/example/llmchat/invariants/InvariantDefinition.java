package com.example.llmchat.invariants;

public record InvariantDefinition(
        String id,
        String title,
        String description,
        String refusalHint,
        boolean hardBlock,
        InvariantActiveWhen activeWhen,
        InvariantGuardType guard) {
}

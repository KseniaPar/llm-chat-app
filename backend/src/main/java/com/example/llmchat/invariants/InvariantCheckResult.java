package com.example.llmchat.invariants;

import java.util.List;

public record InvariantCheckResult(
        List<InvariantDefinition> matched,
        List<InvariantDefinition> hardBlocked,
        List<String> softHints) {

    public boolean hardBlock() {
        return !hardBlocked.isEmpty();
    }
}

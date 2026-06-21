package com.example.llmchat.invariants;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class InvariantsRegistry {

    private final List<InvariantDefinition> rules;

    public InvariantsRegistry(InvariantsProperties properties) {
        this.rules = properties.getRules().stream()
                .map(this::toDefinition)
                .collect(Collectors.toUnmodifiableList());
    }

    public List<InvariantDefinition> all() {
        return rules;
    }

    public Optional<InvariantDefinition> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return rules.stream().filter(rule -> id.equals(rule.id())).findFirst();
    }

    private InvariantDefinition toDefinition(InvariantsProperties.RuleConfig config) {
        return new InvariantDefinition(
                config.getId(),
                config.getTitle(),
                config.getDescription(),
                config.getRefusalHint(),
                config.isHardBlock(),
                parseActiveWhen(config.getActiveWhen()),
                parseGuard(config.getGuard()));
    }

    private static InvariantActiveWhen parseActiveWhen(String value) {
        if (value == null || value.isBlank()) {
            return InvariantActiveWhen.ALWAYS;
        }
        try {
            return InvariantActiveWhen.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return InvariantActiveWhen.ALWAYS;
        }
    }

    private static InvariantGuardType parseGuard(String value) {
        if (value == null || value.isBlank()) {
            return InvariantGuardType.NONE;
        }
        try {
            return InvariantGuardType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return InvariantGuardType.NONE;
        }
    }
}

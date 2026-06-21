package com.example.llmchat.invariants;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "app.agent.invariants")
public class InvariantsProperties {

    private List<RuleConfig> rules = new ArrayList<>();

    public List<RuleConfig> getRules() {
        return rules;
    }

    public void setRules(List<RuleConfig> rules) {
        this.rules = rules != null ? rules : new ArrayList<>();
    }

    public static class RuleConfig {
        private String id;
        private String title;
        private String description;
        private String refusalHint;
        private boolean hardBlock;
        private String activeWhen = "ALWAYS";
        private String guard = "NONE";

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getRefusalHint() {
            return refusalHint;
        }

        public void setRefusalHint(String refusalHint) {
            this.refusalHint = refusalHint;
        }

        public boolean isHardBlock() {
            return hardBlock;
        }

        public void setHardBlock(boolean hardBlock) {
            this.hardBlock = hardBlock;
        }

        public String getActiveWhen() {
            return activeWhen;
        }

        public void setActiveWhen(String activeWhen) {
            this.activeWhen = activeWhen;
        }

        public String getGuard() {
            return guard;
        }

        public void setGuard(String guard) {
            this.guard = guard;
        }
    }
}

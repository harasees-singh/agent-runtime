package io.agentruntime.core.domain.execution;

import java.util.Locale;

/** Execution engines supported by the runner image. */
public enum AgentEngine {
    CLAUDE_CODE("claude-code"),
    OPENCODE("opencode"),
    CODEX("codex");

    private final String id;

    AgentEngine(String id) { this.id = id; }

    public String id() { return id; }

    public static AgentEngine fromId(String value) {
        if (value == null) return null;
        for (AgentEngine engine : values()) {
            if (engine.id.equalsIgnoreCase(value) || engine.name().equalsIgnoreCase(value)) return engine;
        }
        throw new IllegalArgumentException("Unsupported agent engine: " + value.toLowerCase(Locale.ROOT));
    }
}

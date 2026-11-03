package io.agentruntime.core.domain.execution;

import java.util.Locale;

/** Stable model choices exposed by the job API. Provider-specific model IDs live in YAML. */
public enum AgentModel {
    SONNET_4_5("sonnet-4-5"),
    OPUS_4_5("opus-4-5"),
    GPT_6_1_SOL("gpt-6-1-sol"),
    GPT_6_LUNA("gpt-6-luna");

    private final String id;

    AgentModel(String id) { this.id = id; }

    public String id() { return id; }

    public static AgentModel fromId(String value) {
        if (value == null) return null;
        for (AgentModel model : values()) {
            if (model.id.equalsIgnoreCase(value) || model.name().equalsIgnoreCase(value)) return model;
        }
        throw new IllegalArgumentException("Unsupported agent model: " + value.toLowerCase(Locale.ROOT));
    }
}

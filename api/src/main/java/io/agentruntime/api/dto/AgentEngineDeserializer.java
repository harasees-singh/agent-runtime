package io.agentruntime.api.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import io.agentruntime.core.domain.execution.AgentEngine;

import java.io.IOException;

public final class AgentEngineDeserializer extends JsonDeserializer<AgentEngine> {
    @Override
    public AgentEngine deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        try {
            return AgentEngine.fromId(parser.getValueAsString());
        } catch (IllegalArgumentException e) {
            return (AgentEngine) context.handleWeirdStringValue(AgentEngine.class,
                    parser.getValueAsString(), e.getMessage());
        }
    }
}

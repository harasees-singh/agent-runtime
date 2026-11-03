package io.agentruntime.api.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import io.agentruntime.core.domain.execution.AgentModel;

import java.io.IOException;

public final class AgentModelDeserializer extends JsonDeserializer<AgentModel> {
    @Override
    public AgentModel deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        try {
            return AgentModel.fromId(parser.getValueAsString());
        } catch (IllegalArgumentException e) {
            return (AgentModel) context.handleWeirdStringValue(AgentModel.class,
                    parser.getValueAsString(), e.getMessage());
        }
    }
}

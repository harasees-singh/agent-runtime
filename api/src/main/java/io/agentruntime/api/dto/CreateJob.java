package io.agentruntime.api.dto;

import io.agentruntime.core.domain.execution.AgentEngine;
import io.agentruntime.core.domain.execution.AgentModel;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateJob(@NotBlank String agent, @JsonDeserialize(using = AgentEngineDeserializer.class) AgentEngine engine,
                        @JsonDeserialize(using = AgentModelDeserializer.class) AgentModel model,
                        @NotNull @Valid AgentInput input) {}

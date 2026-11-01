package io.agentruntime.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateJob(@NotBlank String agent, @NotNull JsonNode input) {}

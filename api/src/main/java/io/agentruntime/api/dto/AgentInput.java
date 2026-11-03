package io.agentruntime.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentInput(@NotBlank @Size(max = 3000) String prompt) {}

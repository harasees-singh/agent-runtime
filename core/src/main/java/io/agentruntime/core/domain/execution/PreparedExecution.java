package io.agentruntime.core.domain.execution;

public record PreparedExecution(String backendId, String revision, String payload) {}

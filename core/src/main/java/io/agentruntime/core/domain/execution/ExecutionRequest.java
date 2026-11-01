package io.agentruntime.core.domain.execution;

import java.util.UUID;

public record ExecutionRequest(UUID jobId, UUID attemptId, String agent, String inputJson) {}

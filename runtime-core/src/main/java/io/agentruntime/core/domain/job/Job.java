package io.agentruntime.core.domain.job;

import io.agentruntime.core.domain.execution.PreparedExecution;
import io.agentruntime.core.domain.execution.ExecutionRef;

import java.time.Instant;
import java.util.UUID;

public record Job(UUID id, UUID attemptId, String tenantId, String agent, String inputJson,
                  String requestHash, String status, String submissionStatus, String executionStatus,
                  PreparedExecution plan, ExecutionRef executionRef, String error,
                  Instant createdAt, Instant updatedAt) {}

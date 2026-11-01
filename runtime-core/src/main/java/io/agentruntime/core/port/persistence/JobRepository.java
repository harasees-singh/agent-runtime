package io.agentruntime.core.port.persistence;

import io.agentruntime.core.domain.execution.PreparedExecution;
import io.agentruntime.core.domain.execution.ExecutionRef;
import io.agentruntime.core.domain.execution.ExecutionObservation;
import io.agentruntime.core.domain.job.Job;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository {
    Job createOrFind(String tenantId, String key, String hash, UUID jobId, UUID attemptId,
                     String agent, String inputJson, PreparedExecution plan);
    Optional<Job> find(UUID id, String tenantId);
    boolean claim(UUID jobId, Duration lease);
    void markSubmitted(UUID jobId, ExecutionRef ref);
    void markUnknown(UUID jobId, String error);
    void markRejected(UUID jobId, String error);
    void updateObservation(UUID jobId, ExecutionObservation observation);
    List<Job> claimRecoverable(int limit, Duration lease);
    List<Job> claimInspectable(int limit, Duration lease);
}

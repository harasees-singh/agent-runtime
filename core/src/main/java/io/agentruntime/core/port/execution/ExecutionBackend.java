package io.agentruntime.core.port.execution;

import io.agentruntime.core.domain.execution.PreparedExecution;
import io.agentruntime.core.domain.execution.ExecutionRef;
import io.agentruntime.core.domain.execution.ExecutionObservation;
import io.agentruntime.core.domain.execution.SubmissionResult;
import io.agentruntime.core.domain.execution.RecoveryResult;
import io.agentruntime.core.domain.execution.ExecutionRequest;

public interface ExecutionBackend {
    String id();
    PreparedExecution prepare(ExecutionRequest request);
    SubmissionResult submit(PreparedExecution plan);
    RecoveryResult recover(PreparedExecution plan);
    ExecutionObservation inspect(ExecutionRef ref);
    void stop(ExecutionRef ref);
}

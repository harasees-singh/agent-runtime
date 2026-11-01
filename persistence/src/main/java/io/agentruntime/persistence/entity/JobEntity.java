package io.agentruntime.persistence.entity;

import jakarta.persistence.*;
import io.agentruntime.core.domain.job.Job;
import io.agentruntime.core.domain.execution.ExecutionRef;
import io.agentruntime.core.domain.execution.PreparedExecution;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "jobs")
public class JobEntity {
    @Id public UUID id;
    @Column(name = "attempt_id") public UUID attemptId;
    @Column(name = "tenant_id") public String tenantId;
    @Column(name = "idempotency_key") public String idempotencyKey;
    @Column(name = "request_hash") public String requestHash;
    public String agent;
    @Column(name = "input_json") public String inputJson;
    public String status;
    @Column(name = "submission_status") public String submissionStatus;
    @Column(name = "execution_status") public String executionStatus;
    @Column(name = "backend_id") public String backendId;
    @Column(name = "plan_revision") public String planRevision;
    @Column(name = "plan_payload") public String planPayload;
    @Column(name = "execution_handle") public String executionHandle;
    public String error;
    @Column(name = "created_at") public Instant createdAt;
    @Column(name = "updated_at") public Instant updatedAt;
    @Column(name = "lease_until") public Instant leaseUntil;
    protected JobEntity() {}
    public Job toDomain() {
        return new Job(id, attemptId, tenantId, agent, inputJson, requestHash, status, submissionStatus,
                executionStatus, new PreparedExecution(backendId, planRevision, planPayload),
                executionHandle == null ? null : new ExecutionRef(backendId, executionHandle), error, createdAt, updatedAt);
    }
}

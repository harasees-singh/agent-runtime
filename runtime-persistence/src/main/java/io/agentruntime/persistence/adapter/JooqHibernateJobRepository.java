package io.agentruntime.persistence.adapter;

import io.agentruntime.core.port.persistence.JobRepository;
import io.agentruntime.core.domain.job.Job;
import io.agentruntime.core.domain.execution.ExecutionObservation;
import io.agentruntime.core.domain.execution.ExecutionRef;
import io.agentruntime.core.domain.execution.PreparedExecution;
import org.jooq.DSLContext;
import org.jooq.Record;
import jakarta.persistence.EntityManager;
import io.agentruntime.persistence.entity.JobEntity;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class JooqHibernateJobRepository implements JobRepository {
    private final DSLContext dsl;
    private final EntityManager entities;

    public JooqHibernateJobRepository(DSLContext dsl, EntityManager entities) { this.dsl = dsl; this.entities = entities; }

    private static Job read(Record r) {
        String handle = r.get("execution_handle", String.class);
        String backend = r.get("backend_id", String.class);
        return new Job(r.get("id", UUID.class), r.get("attempt_id", UUID.class),
                r.get("tenant_id", String.class), r.get("agent", String.class), r.get("input_json", String.class),
                r.get("request_hash", String.class), r.get("status", String.class), r.get("submission_status", String.class),
                r.get("execution_status", String.class),
                new PreparedExecution(backend, r.get("plan_revision", String.class), r.get("plan_payload", String.class)),
                handle == null ? null : new ExecutionRef(backend, handle), r.get("error", String.class),
                r.get("created_at", OffsetDateTime.class).toInstant(), r.get("updated_at", OffsetDateTime.class).toInstant());
    }

    @Override public Job createOrFind(String tenant, String key, String hash, UUID jobId, UUID attemptId,
                                     String agent, String input, PreparedExecution plan) {
        dsl.execute("""
                INSERT INTO jobs(id, attempt_id, tenant_id, idempotency_key, request_hash, agent, input_json,
                                 status, submission_status, backend_id, plan_revision, plan_payload, lease_until)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'STARTING', 'PENDING', ?, ?, ?, now() + interval '30 seconds')
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                """, jobId, attemptId, tenant, key, hash, agent, input,
                plan.backendId(), plan.revision(), plan.payload());
        return entities.createQuery("select j from JobEntity j where j.tenantId = :tenant and j.idempotencyKey = :key", JobEntity.class)
                .setParameter("tenant", tenant).setParameter("key", key).getSingleResult().toDomain();
    }

    @Override public Optional<Job> find(UUID id, String tenant) {
        return entities.createQuery("select j from JobEntity j where j.id = :id and j.tenantId = :tenant", JobEntity.class)
                .setParameter("id", id).setParameter("tenant", tenant).getResultStream().findFirst().map(JobEntity::toDomain);
    }

    @Override public boolean claim(UUID id, Duration lease) {
        return dsl.execute("""
                UPDATE jobs SET lease_until = now() + (? * interval '1 second'), updated_at = now()
                WHERE id = ? AND submission_status IN ('PENDING', 'UNKNOWN')
                  AND (lease_until IS NULL OR lease_until < now())
                """, lease.toSeconds(), id) == 1;
    }

    @Override public void markSubmitted(UUID id, ExecutionRef ref) {
        dsl.execute("""
                UPDATE jobs SET execution_handle = ?, submission_status = 'SUBMITTED',
                  lease_until = NULL, error = NULL, updated_at = now()
                WHERE id = ? AND submission_status IN ('PENDING', 'UNKNOWN')
                """, ref.handle(), id);
    }

    @Override public void markUnknown(UUID id, String error) {
        dsl.execute("""
                UPDATE jobs SET submission_status = 'UNKNOWN', error = ?,
                  lease_until = now() + interval '15 seconds', updated_at = now()
                WHERE id = ? AND submission_status IN ('PENDING', 'UNKNOWN')
                """, error, id);
    }

    @Override public void markRejected(UUID id, String error) {
        dsl.execute("""
                UPDATE jobs SET status = 'FAILED', submission_status = 'REJECTED', error = ?,
                  lease_until = NULL, updated_at = now()
                WHERE id = ? AND submission_status IN ('PENDING', 'UNKNOWN')
                """, error, id);
    }

    @Override public void updateObservation(UUID id, ExecutionObservation state) {
        switch (state) {
            case RUNNING -> dsl.execute("UPDATE jobs SET execution_status = 'RUNNING', updated_at = now() WHERE id = ? AND status = 'STARTING'", id);
            case STOPPED -> dsl.execute("""
                    UPDATE jobs SET execution_status = 'STOPPED', status = 'FAILED',
                      error = 'Execution stopped without a runner result', updated_at = now()
                    WHERE id = ? AND status = 'STARTING'
                    """, id);
            case STARTING -> dsl.execute("UPDATE jobs SET execution_status = 'STARTING' WHERE id = ? AND status = 'STARTING'", id);
            case UNKNOWN -> { }
        }
    }

    @Override public List<Job> claimRecoverable(int limit, Duration lease) {
        return claimBatch("submission_status IN ('PENDING', 'UNKNOWN')", limit, lease);
    }

    @Override public List<Job> claimInspectable(int limit, Duration lease) {
        return claimBatch("submission_status = 'SUBMITTED' AND status IN ('STARTING', 'RUNNING')", limit, lease);
    }

    private List<Job> claimBatch(String predicate, int limit, Duration lease) {
        return dsl.fetch("""
                WITH due AS (
                  SELECT id FROM jobs WHERE %s AND (lease_until IS NULL OR lease_until < now())
                  ORDER BY updated_at LIMIT ? FOR UPDATE SKIP LOCKED
                )
                UPDATE jobs SET lease_until = now() + (? * interval '1 second')
                FROM due WHERE jobs.id = due.id RETURNING jobs.*
                """.formatted(predicate), limit, lease.toSeconds()).map(JooqHibernateJobRepository::read);
    }
}

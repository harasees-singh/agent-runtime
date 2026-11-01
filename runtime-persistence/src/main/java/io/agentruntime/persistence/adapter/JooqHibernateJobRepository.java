package io.agentruntime.persistence.adapter;

import io.agentruntime.core.domain.execution.ExecutionObservation;
import io.agentruntime.core.domain.execution.ExecutionRef;
import io.agentruntime.core.domain.execution.PreparedExecution;
import io.agentruntime.core.domain.job.Job;
import io.agentruntime.core.port.persistence.JobRepository;
import io.agentruntime.persistence.entity.JobEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public class JooqHibernateJobRepository implements JobRepository {
    private static final Table<Record> JOBS = DSL.table(DSL.name("jobs"));
    private static final Field<UUID> ID = field("id", UUID.class);
    private static final Field<UUID> ATTEMPT = field("attempt_id", UUID.class);
    private static final Field<String> TENANT = field("tenant_id", String.class);
    private static final Field<String> KEY = field("idempotency_key", String.class);
    private static final Field<String> HASH = field("request_hash", String.class);
    private static final Field<String> AGENT = field("agent", String.class);
    private static final Field<String> INPUT = field("input_json", String.class);
    private static final Field<String> STATUS = field("status", String.class);
    private static final Field<String> SUBMISSION = field("submission_status", String.class);
    private static final Field<String> BACKEND = field("backend_id", String.class);
    private static final Field<String> REVISION = field("plan_revision", String.class);
    private static final Field<String> PLAN = field("plan_payload", String.class);
    private static final Field<OffsetDateTime> LEASE = field("lease_until", OffsetDateTime.class);
    private static final Field<OffsetDateTime> UPDATED = field("updated_at", OffsetDateTime.class);

    private final DSLContext dsl;
    private final EntityManager entities;

    public JooqHibernateJobRepository(DSLContext dsl, EntityManager entities) {
        this.dsl = dsl;
        this.entities = entities;
    }

    private static <T> Field<T> field(String name, Class<T> type) {
        return DSL.field(DSL.name(name), type);
    }

    private OffsetDateTime databaseTime() {
        return dsl.select(DSL.currentOffsetDateTime()).fetchOne(0, OffsetDateTime.class);
    }

    @Override
    @Transactional
    public Job createOrFind(String tenant, String key, String hash, UUID jobId, UUID attemptId,
                            String agent, String input, PreparedExecution plan) {
        dsl.insertInto(JOBS)
                .set(ID, jobId).set(ATTEMPT, attemptId).set(TENANT, tenant).set(KEY, key)
                .set(HASH, hash).set(AGENT, agent).set(INPUT, input).set(STATUS, "STARTING")
                .set(SUBMISSION, "PENDING").set(BACKEND, plan.backendId())
                .set(REVISION, plan.revision()).set(PLAN, plan.payload())
                .set(LEASE, databaseTime().plusSeconds(30))
                .onConflict(TENANT, KEY).doNothing().execute();
        return entities.createQuery("select j from JobEntity j where j.tenantId = :tenant and j.idempotencyKey = :key", JobEntity.class)
                .setParameter("tenant", tenant).setParameter("key", key).getSingleResult().toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Job> find(UUID id, String tenant) {
        return entities.createQuery("select j from JobEntity j where j.id = :id and j.tenantId = :tenant", JobEntity.class)
                .setParameter("id", id).setParameter("tenant", tenant).getResultStream().findFirst().map(JobEntity::toDomain);
    }

    @Override
    @Transactional
    public boolean claim(UUID id, Duration lease) {
        OffsetDateTime now = databaseTime();
        return dsl.update(JOBS).set(LEASE, now.plusSeconds(lease.toSeconds())).set(UPDATED, now)
                .where(ID.eq(id).and(SUBMISSION.in("PENDING", "UNKNOWN"))
                        .and(LEASE.isNull().or(LEASE.lt(now)))).execute() == 1;
    }

    @Override
    @Transactional
    public void markSubmitted(UUID id, ExecutionRef ref) {
        updatePending(id, job -> {
            job.executionHandle = ref.handle();
            job.submissionStatus = "SUBMITTED";
            job.leaseUntil = null;
            job.error = null;
        });
    }

    @Override
    @Transactional
    public void markUnknown(UUID id, String error) {
        updatePending(id, job -> {
            job.submissionStatus = "UNKNOWN";
            job.error = error;
            job.leaseUntil = databaseTime().plusSeconds(15).toInstant();
        });
    }

    @Override
    @Transactional
    public void markRejected(UUID id, String error) {
        updatePending(id, job -> {
            job.status = "FAILED";
            job.submissionStatus = "REJECTED";
            job.error = error;
            job.leaseUntil = null;
        });
    }

    private void updatePending(UUID id, Consumer<JobEntity> update) {
        JobEntity job = entities.find(JobEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (job != null && ("PENDING".equals(job.submissionStatus) || "UNKNOWN".equals(job.submissionStatus))) {
            update.accept(job);
            job.updatedAt = databaseTime().toInstant();
        }
    }

    @Override
    @Transactional
    public void updateObservation(UUID id, ExecutionObservation state) {
        if (state == ExecutionObservation.UNKNOWN) return;
        JobEntity job = entities.find(JobEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (job == null || !"STARTING".equals(job.status)) return;
        job.executionStatus = state.name();
        if (state == ExecutionObservation.STOPPED) {
            job.status = "FAILED";
            job.error = "Execution stopped without a runner result";
        }
        if (state != ExecutionObservation.STARTING) job.updatedAt = databaseTime().toInstant();
    }

    @Override
    @Transactional
    public List<Job> claimRecoverable(int limit, Duration lease) {
        return claimBatch(SUBMISSION.in("PENDING", "UNKNOWN"), limit, lease);
    }

    @Override
    @Transactional
    public List<Job> claimInspectable(int limit, Duration lease) {
        return claimBatch(SUBMISSION.eq("SUBMITTED").and(STATUS.in("STARTING", "RUNNING")), limit, lease);
    }

    private List<Job> claimBatch(Condition eligible, int limit, Duration lease) {
        OffsetDateTime now = databaseTime();
        List<UUID> ids = dsl.select(ID).from(JOBS)
                .where(eligible.and(LEASE.isNull().or(LEASE.lt(now))))
                .orderBy(UPDATED).limit(limit).forUpdate().skipLocked().fetch(ID);
        if (ids.isEmpty()) return List.of();
        dsl.update(JOBS).set(LEASE, now.plusSeconds(lease.toSeconds())).where(ID.in(ids)).execute();
        return ids.stream().map(id -> entities.find(JobEntity.class, id).toDomain()).toList();
    }
}

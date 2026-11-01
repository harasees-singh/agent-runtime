package io.agentruntime.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentruntime.core.port.execution.ExecutionBackend;
import io.agentruntime.core.port.persistence.JobRepository;
import io.agentruntime.core.domain.job.Job;
import io.agentruntime.core.domain.execution.ExecutionObservation;
import io.agentruntime.core.domain.execution.ExecutionRef;
import io.agentruntime.core.domain.execution.ExecutionRequest;
import io.agentruntime.core.domain.execution.PreparedExecution;
import io.agentruntime.core.domain.execution.RecoveryResult;
import io.agentruntime.core.domain.execution.SubmissionResult;
import io.agentruntime.core.exception.LaunchUnknownException;
import io.agentruntime.dispatcher.exception.IdempotencyConflictException;
import io.agentruntime.dispatcher.application.JobDispatcher;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JobDispatcherTest {
    private final ObjectMapper json = new ObjectMapper();
    private final MemoryJobs jobs = new MemoryJobs();
    private final FakeBackend backend = new FakeBackend(jobs);
    private final JobDispatcher dispatcher = new JobDispatcher(jobs, List.of(backend), "fake", json);

    @Test void persistsIntentBeforeLaunchAndReusesSameKey() throws Exception {
        var first = dispatcher.submit("tenant-a", "key-1", "agent:1", json.readTree("{\"b\":2,\"a\":1}"));
        assertEquals("SUBMITTED", first.submissionStatus());
        assertEquals(1, backend.submissions);
        var retry = dispatcher.submit("tenant-a", "key-1", "agent:1", json.readTree("{\"a\":1,\"b\":2}"));
        assertEquals(first.id(), retry.id());
        assertEquals(1, backend.submissions);
        assertThrows(IdempotencyConflictException.class,
                () -> dispatcher.submit("tenant-a", "key-1", "agent:1", json.readTree("{\"a\":9}")));
    }

    @Test void uncertainLaunchKeepsStableJobForRecovery() throws Exception {
        backend.unknown = true;
        var first = dispatcher.submit("tenant-a", "key-2", "agent:1", json.readTree("{}"));
        assertEquals("UNKNOWN", first.submissionStatus());
        assertNull(first.executionRef());
        backend.unknown = false;
        jobs.leaseExpired = true;
        dispatcher.reconcile();
        var recovered = dispatcher.current(first.id(), "tenant-a");
        assertEquals("SUBMITTED", recovered.submissionStatus());
        assertEquals(first.attemptId(), recovered.attemptId());
    }

    private static class FakeBackend implements ExecutionBackend {
        final MemoryJobs jobs;
        int submissions;
        boolean unknown;
        FakeBackend(MemoryJobs jobs) { this.jobs = jobs; }
        public String id() { return "fake"; }
        public PreparedExecution prepare(ExecutionRequest r) { return new PreparedExecution(id(), "1", r.attemptId().toString()); }
        public SubmissionResult submit(PreparedExecution plan) {
            assertTrue(jobs.byKey.values().stream().anyMatch(j -> j.plan().equals(plan)), "intent must exist before launch");
            submissions++;
            if (unknown) throw new LaunchUnknownException("timeout");
            return new SubmissionResult(new ExecutionRef(id(), plan.payload()));
        }
        public RecoveryResult recover(PreparedExecution plan) { return RecoveryResult.found(new ExecutionRef(id(), plan.payload())); }
        public ExecutionObservation inspect(ExecutionRef ref) { return ExecutionObservation.STARTING; }
        public void stop(ExecutionRef ref) { }
    }

    private static class MemoryJobs implements JobRepository {
        final Map<String, Job> byKey = new HashMap<>();
        boolean leaseExpired;
        public Job createOrFind(String tenant, String key, String hash, UUID id, UUID attempt, String agent,
                                String input, PreparedExecution plan) {
            return byKey.computeIfAbsent(tenant + "/" + key, ignored -> new Job(id, attempt, tenant, agent,
                    input, hash, "STARTING", "PENDING", "UNKNOWN", plan, null, null, Instant.now(), Instant.now()));
        }
        public Optional<Job> find(UUID id, String tenant) {
            return byKey.values().stream().filter(j -> j.id().equals(id) && j.tenantId().equals(tenant)).findFirst();
        }
        public boolean claim(UUID id, Duration lease) { return leaseExpired; }
        public void markSubmitted(UUID id, ExecutionRef ref) { replace(id, j -> copy(j, "SUBMITTED", ref)); }
        public void markUnknown(UUID id, String error) { replace(id, j -> copy(j, "UNKNOWN", null)); }
        public void markRejected(UUID id, String error) { replace(id, j -> copy(j, "REJECTED", null)); }
        public void updateObservation(UUID id, ExecutionObservation state) { }
        public List<Job> claimRecoverable(int limit, Duration lease) {
            if (!leaseExpired) return List.of();
            return byKey.values().stream().filter(j -> j.submissionStatus().equals("UNKNOWN")).toList();
        }
        public List<Job> claimInspectable(int limit, Duration lease) { return List.of(); }
        private void replace(UUID id, java.util.function.Function<Job, Job> f) {
            byKey.replaceAll((k, j) -> j.id().equals(id) ? f.apply(j) : j);
        }
        private Job copy(Job j, String status, ExecutionRef ref) {
            return new Job(j.id(), j.attemptId(), j.tenantId(), j.agent(), j.inputJson(), j.requestHash(),
                    j.status(), status, j.executionStatus(), j.plan(), ref, j.error(), j.createdAt(), Instant.now());
        }
    }
}

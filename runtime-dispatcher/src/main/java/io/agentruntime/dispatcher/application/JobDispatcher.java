package io.agentruntime.dispatcher.application;

import io.agentruntime.dispatcher.exception.JobNotFoundException;
import io.agentruntime.dispatcher.exception.IdempotencyConflictException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentruntime.core.port.execution.ExecutionBackend;
import io.agentruntime.core.port.persistence.JobRepository;
import io.agentruntime.core.domain.job.Job;
import io.agentruntime.core.domain.execution.ExecutionRequest;
import io.agentruntime.core.domain.execution.PreparedExecution;
import io.agentruntime.core.domain.execution.RecoveryResult;
import io.agentruntime.core.domain.execution.SubmissionResult;
import io.agentruntime.core.exception.LaunchRejectedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public final class JobDispatcher {
    private final JobRepository jobs;
    private final Map<String, ExecutionBackend> backends;
    private final String defaultBackend;
    private final ObjectMapper json;

    public JobDispatcher(JobRepository jobs, List<ExecutionBackend> backends, String defaultBackend, ObjectMapper json) {
        this.jobs = jobs;
        this.backends = backends.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(ExecutionBackend::id, b -> b));
        if (!this.backends.containsKey(defaultBackend)) throw new IllegalArgumentException("Unknown default backend: " + defaultBackend);
        this.defaultBackend = defaultBackend;
        this.json = json;
    }

    public Job submit(String tenant, String key, String agent, JsonNode input) {
        String canonical = canonical(input).toString();
        String hash = sha256(agent + "\n" + canonical);
        UUID id = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        ExecutionBackend backend = backends.get(defaultBackend);
        PreparedExecution prepared = backend.prepare(new ExecutionRequest(id, attempt, agent, canonical));
        Job job = jobs.createOrFind(tenant, key, hash, id, attempt, agent, canonical, prepared);
        if (!hash.equals(job.requestHash())) throw new IdempotencyConflictException();
        if (!"PENDING".equals(job.submissionStatus()) && !"UNKNOWN".equals(job.submissionStatus())) return job;
        if (!id.equals(job.id()) && !jobs.claim(job.id(), Duration.ofSeconds(30))) return current(job.id(), tenant);
        try {
            SubmissionResult accepted = backends.get(job.plan().backendId()).submit(job.plan());
            jobs.markSubmitted(job.id(), accepted.ref());
        } catch (LaunchRejectedException e) {
            jobs.markRejected(job.id(), "Execution launch rejected");
        } catch (RuntimeException e) {
            jobs.markUnknown(job.id(), "Launch outcome unknown");
        }
        return current(job.id(), tenant);
    }

    public Job current(UUID id, String tenant) {
        return jobs.find(id, tenant).orElseThrow(JobNotFoundException::new);
    }

    public void reconcile() {
        for (Job job : jobs.claimRecoverable(25, Duration.ofSeconds(30))) {
            try {
                RecoveryResult result = backend(job).recover(job.plan());
                if (result.resolved()) jobs.markSubmitted(job.id(), result.ref());
                else jobs.markUnknown(job.id(), "Launch outcome unresolved");
            } catch (LaunchRejectedException e) {
                jobs.markRejected(job.id(), "Execution launch rejected");
            } catch (RuntimeException e) {
                jobs.markUnknown(job.id(), "Launch outcome unknown");
            }
        }
        for (Job job : jobs.claimInspectable(25, Duration.ofSeconds(30))) {
            try { jobs.updateObservation(job.id(), backend(job).inspect(job.executionRef())); }
            catch (RuntimeException ignored) { /* Retry on the next lease. */ }
        }
    }

    private ExecutionBackend backend(Job job) {
        ExecutionBackend backend = backends.get(job.plan().backendId());
        if (backend == null) throw new IllegalStateException("Stored backend is unavailable: " + job.plan().backendId());
        return backend;
    }

    private JsonNode canonical(JsonNode node) {
        if (node == null) return json.getNodeFactory().nullNode();
        if (node.isArray()) {
            var array = json.createArrayNode();
            node.forEach(n -> array.add(canonical(n)));
            return array;
        }
        if (node.isObject()) {
            var object = json.createObjectNode();
            var sorted = new TreeMap<String, JsonNode>();
            node.fields().forEachRemaining(e -> sorted.put(e.getKey(), e.getValue()));
            sorted.forEach((k, v) -> object.set(k, canonical(v)));
            return object;
        }
        return node;
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}

package io.agentruntime.api.controller;

import io.agentruntime.api.dto.CreateJob;
import io.agentruntime.api.dto.JobView;
import io.agentruntime.core.domain.job.Job;
import io.agentruntime.dispatcher.exception.IdempotencyConflictException;
import io.agentruntime.dispatcher.exception.JobNotFoundException;
import io.agentruntime.dispatcher.application.JobDispatcher;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/jobs")
public class JobController {
    public record Completion(UUID attemptId, String callbackToken, String result, String error) {}
    private final JobDispatcher dispatcher;
    private final boolean localTenantHeaders;
    public JobController(JobDispatcher dispatcher,
                         @Value("${runtime.security.local-tenant-headers:false}") boolean localTenantHeaders) {
        this.dispatcher = dispatcher; this.localTenantHeaders = localTenantHeaders;
    }

    @PostMapping
    public ResponseEntity<JobView> create(@RequestHeader("Idempotency-Key") String key,
                                           @RequestHeader(value = "X-Tenant-Id", required = false) String localTenant,
                                           Authentication auth, @Valid @RequestBody CreateJob body) {
        if (key.isBlank() || key.length() > 256) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Idempotency-Key");
        Job job = dispatcher.submit(tenant(auth, localTenant), key, body.agent(), body.engine(), body.model(), body.input().prompt());
        URI location = URI.create("/v1/jobs/" + job.id());
        HttpStatus code = switch (job.submissionStatus()) {
            case "SUBMITTED" -> HttpStatus.ACCEPTED;
            case "REJECTED" -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        if ("SUBMITTED".equals(job.submissionStatus()) &&
                ("FAILED".equals(job.status()) || "SUCCEEDED".equals(job.status()))) code = HttpStatus.OK;
        return ResponseEntity.status(code).location(location)
                .headers(code == HttpStatus.SERVICE_UNAVAILABLE ? retryHeader() : new HttpHeaders())
                .body(JobView.from(job));
    }

    @GetMapping("/{id}")
    public JobView get(@PathVariable UUID id,
                       @RequestHeader(value = "X-Tenant-Id", required = false) String localTenant,
                       Authentication auth) {
        return JobView.from(dispatcher.current(id, tenant(auth, localTenant)));
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<Void> complete(@PathVariable UUID id, @RequestBody Completion completion) {
        if (completion.result() != null && completion.result().length() > 100_000)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Result exceeds 100 KB");
        boolean accepted = dispatcher.complete(id, completion.attemptId(), completion.callbackToken(),
                completion.result(), completion.error());
        return accepted ? ResponseEntity.noContent().build() : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    private String tenant(Authentication auth, String localTenant) {
        if (localTenantHeaders) {
            if (localTenant == null || localTenant.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant-Id required locally");
            return localTenant;
        }
        if (auth instanceof JwtAuthenticationToken jwt) {
            String tenant = jwt.getToken().getClaimAsString("tenant_id");
            if (tenant != null && !tenant.isBlank()) return tenant;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant claim required");
    }

    private HttpHeaders retryHeader() {
        HttpHeaders headers = new HttpHeaders(); headers.set(HttpHeaders.RETRY_AFTER, "3"); return headers;
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, String>> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Idempotency-Key used for a different request"));
    }

    @ExceptionHandler(JobNotFoundException.class)
    public ResponseEntity<Map<String, String>> missing() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Job not found"));
    }
}

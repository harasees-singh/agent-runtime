package io.agentruntime.api.dto;

import io.agentruntime.core.domain.job.Job;
import java.time.Instant;
import java.util.UUID;

public record JobView(UUID id, UUID attemptId, String status, String submissionOutcome, String executionStatus,
                      String agent, String result, String error, Instant createdAt) {
    public static JobView from(Job job) {
        return new JobView(job.id(), job.attemptId(), job.status(), job.submissionStatus(),
                job.executionStatus(), job.agent(), job.result(), job.error(), job.createdAt());
    }
}

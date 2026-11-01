package io.agentruntime.ecs.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentruntime.core.port.execution.ExecutionBackend;
import io.agentruntime.core.domain.execution.ExecutionObservation;
import io.agentruntime.core.domain.execution.ExecutionRef;
import io.agentruntime.core.domain.execution.ExecutionRequest;
import io.agentruntime.core.domain.execution.PreparedExecution;
import io.agentruntime.core.domain.execution.RecoveryResult;
import io.agentruntime.core.domain.execution.SubmissionResult;
import io.agentruntime.core.exception.LaunchRejectedException;
import io.agentruntime.core.exception.LaunchUnknownException;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.*;
import java.util.ArrayList;
import java.util.List;
import java.time.Instant;
import java.time.Duration;

public final class EcsFargateBackend implements ExecutionBackend {
    public record Settings(String cluster, String taskDefinition, String container, List<String> subnets,
                           List<String> securityGroups, String platformVersion) {}
    private record Plan(String cluster, String taskDefinition, String container, List<String> subnets,
                        List<String> securityGroups, String platformVersion, String attemptId, String jobId,
                        long preparedAtEpochMillis) {}
    private record Handle(String cluster, String taskArn) {}

    private final EcsClient ecs;
    private final ObjectMapper json;
    private final Settings settings;

    public EcsFargateBackend(EcsClient ecs, ObjectMapper json, Settings settings) {
        this.ecs = ecs; this.json = json; this.settings = settings;
    }

    @Override public String id() { return "ecs-fargate"; }

    @Override public PreparedExecution prepare(ExecutionRequest request) {
        try {
            return new PreparedExecution(id(), "1", json.writeValueAsString(new Plan(
                    settings.cluster(), settings.taskDefinition(), settings.container(),
                    settings.subnets(), settings.securityGroups(), settings.platformVersion(),
                    request.attemptId().toString(), request.jobId().toString(), Instant.now().toEpochMilli())));
        } catch (JsonProcessingException e) { throw new IllegalStateException("Could not prepare ECS launch", e); }
    }

    private Plan read(PreparedExecution prepared) {
        if (!id().equals(prepared.backendId()) || !"1".equals(prepared.revision()))
            throw new IllegalArgumentException("Unsupported ECS launch plan");
        try { return json.readValue(prepared.payload(), Plan.class); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Invalid ECS launch plan", e); }
    }

    private RunTaskRequest request(Plan p) {
        var network = NetworkConfiguration.builder().awsvpcConfiguration(AwsVpcConfiguration.builder()
                .subnets(p.subnets()).securityGroups(p.securityGroups())
                .assignPublicIp(AssignPublicIp.DISABLED).build()).build();
        var overrides = TaskOverride.builder().containerOverrides(ContainerOverride.builder()
                .name(p.container()).environment(
                        KeyValuePair.builder().name("RUNTIME_JOB_ID").value(p.jobId()).build(),
                        KeyValuePair.builder().name("RUNTIME_ATTEMPT_ID").value(p.attemptId()).build())
                .build()).build();
        return RunTaskRequest.builder().cluster(p.cluster()).taskDefinition(p.taskDefinition())
                .launchType(LaunchType.FARGATE).platformVersion(p.platformVersion()).count(1)
                .clientToken(p.attemptId()).startedBy(p.attemptId()).networkConfiguration(network)
                .overrides(overrides).tags(
                        Tag.builder().key("runtime:job-id").value(p.jobId()).build(),
                        Tag.builder().key("runtime:attempt-id").value(p.attemptId()).build()).build();
    }

    private ExecutionRef ref(String cluster, String arn) {
        try { return new ExecutionRef(id(), json.writeValueAsString(new Handle(cluster, arn))); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private Handle handle(ExecutionRef ref) {
        if (!id().equals(ref.backendId())) throw new IllegalArgumentException("Wrong execution backend");
        try { return json.readValue(ref.handle(), Handle.class); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Invalid ECS execution reference", e); }
    }

    @Override public SubmissionResult submit(PreparedExecution prepared) {
        Plan p = read(prepared);
        try {
            RunTaskResponse response = ecs.runTask(request(p));
            if (!response.failures().isEmpty())
                throw new LaunchRejectedException("ECS rejected launch: " + response.failures().getFirst().reason());
            if (response.tasks().size() != 1 || response.tasks().getFirst().taskArn() == null)
                throw new LaunchUnknownException("ECS did not return exactly one task ARN");
            return new SubmissionResult(ref(p.cluster(), response.tasks().getFirst().taskArn()));
        } catch (LaunchRejectedException | LaunchUnknownException e) { throw e; }
        catch (EcsException e) {
            if (e.isThrottlingException() || e.statusCode() >= 500)
                throw new LaunchUnknownException("ECS launch outcome is uncertain", e);
            throw new LaunchRejectedException("ECS rejected launch: " + e.awsErrorDetails().errorCode());
        } catch (RuntimeException e) { throw new LaunchUnknownException("ECS launch outcome is uncertain", e); }
    }

    @Override public RecoveryResult recover(PreparedExecution prepared) {
        Plan p = read(prepared);
        try {
            List<String> arns = new ArrayList<>();
            String next = null;
            do {
                ListTasksResponse page = ecs.listTasks(ListTasksRequest.builder().cluster(p.cluster())
                        .startedBy(p.attemptId()).nextToken(next).build());
                arns.addAll(page.taskArns()); next = page.nextToken();
            } while (next != null);
            if (arns.size() > 1) throw new LaunchUnknownException("Multiple ECS tasks match attempt " + p.attemptId());
            if (arns.size() == 1) {
                var response = ecs.describeTasks(DescribeTasksRequest.builder().cluster(p.cluster())
                        .tasks(arns.getFirst()).include(TaskField.TAGS).build());
                if (response.tasks().size() != 1 || !p.taskDefinition().equals(response.tasks().getFirst().taskDefinitionArn())
                        || response.tasks().getFirst().tags().stream().noneMatch(
                        t -> "runtime:attempt-id".equals(t.key()) && p.attemptId().equals(t.value()))
                        || response.tasks().getFirst().tags().stream().noneMatch(
                        t -> "runtime:job-id".equals(t.key()) && p.jobId().equals(t.value())))
                    throw new LaunchUnknownException("ECS task correlation could not be verified");
                return RecoveryResult.found(ref(p.cluster(), arns.getFirst()));
            }
            if (Duration.between(Instant.ofEpochMilli(p.preparedAtEpochMillis()), Instant.now()).compareTo(Duration.ofMinutes(30)) < 0)
                return RecoveryResult.found(submit(prepared).ref());
            return RecoveryResult.unresolved();
        } catch (LaunchUnknownException e) { throw e; }
        catch (RuntimeException e) { throw new LaunchUnknownException("ECS recovery is unavailable", e); }
    }

    @Override public ExecutionObservation inspect(ExecutionRef ref) {
        try {
            Handle h = handle(ref);
            var response = ecs.describeTasks(DescribeTasksRequest.builder().cluster(h.cluster())
                    .tasks(h.taskArn()).build());
            if (response.tasks().isEmpty()) return ExecutionObservation.UNKNOWN;
            return switch (response.tasks().getFirst().lastStatus()) {
                case "RUNNING" -> ExecutionObservation.RUNNING;
                case "STOPPED" -> ExecutionObservation.STOPPED;
                default -> ExecutionObservation.STARTING;
            };
        } catch (RuntimeException e) { return ExecutionObservation.UNKNOWN; }
    }

    @Override public void stop(ExecutionRef ref) {
        Handle h = handle(ref);
        ecs.stopTask(StopTaskRequest.builder().cluster(h.cluster()).task(h.taskArn())
                .reason("Agent runtime cancellation").build());
    }
}

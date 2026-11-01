package io.agentruntime.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentruntime.core.domain.execution.ExecutionRequest;
import io.agentruntime.ecs.adapter.EcsFargateBackend;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.*;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EcsFargateBackendTest {
    @Test void retryUsesPersistedAttemptAndIdenticalRunTaskRequest() {
        EcsClient ecs = mock(EcsClient.class);
        String arn = "arn:aws:ecs:us-east-1:000000000000:task/cluster/abc";
        when(ecs.runTask(any(RunTaskRequest.class))).thenReturn(RunTaskResponse.builder()
                .tasks(Task.builder().taskArn(arn).build()).build());
        when(ecs.listTasks(any(ListTasksRequest.class))).thenReturn(ListTasksResponse.builder().build());
        var backend = new EcsFargateBackend(ecs, new ObjectMapper(), new EcsFargateBackend.Settings(
                "cluster", "arn:aws:ecs:us-east-1:000000000000:task-definition/runner:1", "runner",
                List.of("subnet-1"), List.of("sg-1"), "LATEST"));
        UUID jobId = UUID.randomUUID(), attemptId = UUID.randomUUID();
        var plan = backend.prepare(new ExecutionRequest(jobId, attemptId, "agent:1", "{}"));

        var accepted = backend.submit(plan);
        var recovered = backend.recover(plan);

        assertTrue(recovered.resolved());
        assertEquals(accepted.ref(), recovered.ref());
        var requests = ArgumentCaptor.forClass(RunTaskRequest.class);
        verify(ecs, times(2)).runTask(requests.capture());
        assertEquals(requests.getAllValues().get(0), requests.getAllValues().get(1));
        assertEquals(attemptId.toString(), requests.getValue().clientToken());
        assertEquals(attemptId.toString(), requests.getValue().startedBy());
        assertEquals(1, requests.getValue().count());
    }
}

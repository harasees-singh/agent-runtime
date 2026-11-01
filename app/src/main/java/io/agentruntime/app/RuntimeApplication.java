package io.agentruntime.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentruntime.core.port.execution.ExecutionBackend;
import io.agentruntime.core.port.persistence.JobRepository;
import io.agentruntime.dispatcher.application.JobDispatcher;
import io.agentruntime.ecs.adapter.EcsFargateBackend;
import io.agentruntime.persistence.adapter.JooqHibernateJobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.*;
import org.jooq.DSLContext;
import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import java.net.URI;
import java.time.Duration;
import java.util.List;

@SpringBootApplication(scanBasePackages = "io.agentruntime")
@EntityScan(basePackages = "io.agentruntime.persistence.entity")
@EnableScheduling
public class RuntimeApplication {
    public static void main(String[] args) { SpringApplication.run(RuntimeApplication.class, args); }

    @Bean JobRepository jobRepository(DSLContext dsl, EntityManager entities) { return new JooqHibernateJobRepository(dsl, entities); }

    @Bean EcsClient ecsClient(@Value("${runtime.ecs.region:us-east-1}") String region,
                             @Value("${runtime.ecs.endpoint:}") String endpoint) {
        var builder = EcsClient.builder().region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(8)))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(10))
                        .apiCallAttemptTimeout(Duration.ofSeconds(8)));
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")));
        return builder.build();
    }

    @Bean ExecutionBackend ecsBackend(EcsClient ecs, ObjectMapper json,
            @Value("${runtime.ecs.cluster}") String cluster,
            @Value("${runtime.ecs.task-definition}") String taskDefinition,
            @Value("${runtime.ecs.container}") String container,
            @Value("${runtime.ecs.subnets}") List<String> subnets,
            @Value("${runtime.ecs.security-groups}") List<String> securityGroups,
            @Value("${runtime.ecs.platform-version:LATEST}") String platformVersion) {
        return new EcsFargateBackend(ecs, json, new EcsFargateBackend.Settings(cluster, taskDefinition,
                container, subnets, securityGroups, platformVersion));
    }

    @Bean JobDispatcher dispatcher(JobRepository jobs, List<ExecutionBackend> backends, ObjectMapper json,
            @Value("${runtime.execution.default-backend:ecs-fargate}") String defaultBackend) {
        return new JobDispatcher(jobs, backends, defaultBackend, json);
    }

    @Bean SecurityFilterChain security(HttpSecurity http,
            @Value("${runtime.security.local-tenant-headers:false}") boolean local) throws Exception {
        http.csrf(csrf -> csrf.disable());
        if (local) http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
        else http.authorizeHttpRequests(a -> a.requestMatchers("/actuator/health/**").permitAll()
                .anyRequest().authenticated()).oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean ReconciliationTicker reconciliationTicker(JobDispatcher dispatcher) {
        return new ReconciliationTicker(dispatcher);
    }

    public static final class ReconciliationTicker {
        private final JobDispatcher dispatcher;
        ReconciliationTicker(JobDispatcher dispatcher) { this.dispatcher = dispatcher; }
        @Scheduled(fixedDelayString = "${runtime.reconcile.delay-ms:10000}")
        public void tick() { dispatcher.reconcile(); }
    }
}

# Agent runtime: first implementation slice

This repository now contains the direct launch path from the [HLD](HLD.md). One Spring Boot process accepts `POST /v1/jobs`, saves a durable intent in PostgreSQL, calls the execution backend, records its handle, and returns `202`. The ECS/Fargate backend is a separate Maven module. A scheduled reconciler recovers uncertain submissions by attempt ID and observes accepted tasks. There is no SQS queue or application launch cap.

This slice launches an ECS task and tracks its infrastructure state. It does not yet implement runner callbacks, agent package execution, warm runners, artifacts, cancellation, or completion results. The local task is a five minute smoke container; when it exits without a runner result, the job becomes `FAILED`.

## Code structure

- `core`: domain models under `domain`, contracts under `port`, and execution errors under `exception`.
- `dispatcher`: job orchestration under `application` and application errors under `exception`.
- `api`: HTTP controllers under `controller` and request/response types under `dto`.
- `ecs-fargate` and `persistence`: infrastructure implementations under `adapter`. Persistence uses Hibernate for mapped reads and state changes, and jOOQ for conflict-safe inserts and atomic lease claims; Flyway owns the schema.
- `app`: application startup and dependency wiring.

Dependencies point toward the core contracts. The application module assembles the adapters and dispatcher.

## Build and run locally

Requires Java 21+, Maven 3.6.3+, Docker, and an accessible Docker socket. On Docker Desktop, set `LOCAL_DOCKER_SOCKET` to the Unix socket path if it differs from `/var/run/docker.sock`.

```bash
mvn test
bash scripts/local-up.sh
```

The script starts PostgreSQL and Floci, creates a local ECS cluster, VPC, subnet, security group and task definition, writes `.env.local`, then builds and starts the API container. It uses local AWS credentials only. Repeated runs create new local network resources and task definition revisions.

Submit and inspect a job:

```bash
curl -i -X POST http://localhost:8080/v1/jobs \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-Id: demo' \
  -H 'Idempotency-Key: demo-001' \
  -d '{"agent":"smoke:1","input":{"message":"hello"}}'

curl -H 'X-Tenant-Id: demo' http://localhost:8080/v1/jobs/JOB_ID
```

The `local` Spring profile accepts `X-Tenant-Id` as a development fixture. Outside that profile, the API requires a JWT with a `tenant_id` claim; configure the Spring resource server issuer or JWK URI. The execution plan captures the task definition and network chosen at submission time, so later configuration changes do not change an in-flight launch. A `503` response with `submissionOutcome=UNKNOWN` may still launch a task; poll its stable job ID or retry with the same idempotency key.

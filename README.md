# Agent Runtime

Agent Runtime accepts job requests, records them in PostgreSQL, and starts an isolated ECS task. This repository currently provides a local smoke path for validating API admission, persistence, and ECS task launch through Floci.

## Requirements

- Docker Desktop with Docker Compose
- Java 21 and Maven 3.9+ if you want to build or test outside Docker
- An internet connection the first time Docker needs to download images or Maven dependencies

On macOS, Docker Desktop must expose its default Docker socket at `/var/run/docker.sock`. The Floci container uses that socket to start ECS task containers. Compose sets `FLOCI_RUN_AS_ROOT=true` because the mounted socket is writable only by root in this local setup.

## Start locally

From the repository root, run:

```bash
bash scripts/local-up.sh
```

The script starts PostgreSQL and Floci, creates a local ECS cluster and task definition, then builds and starts the API. The first startup can take a few minutes while Docker downloads dependencies and images.

Check that the API and Floci respond:

```bash
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:4566/
```

The API is available at `http://localhost:8080`; Floci is available at `http://localhost:4566`. PostgreSQL is available on port `5432` for local inspection.

## Submit and inspect a run

Submit a job with a unique idempotency key:

```bash
curl -i -X POST http://localhost:8080/v1/jobs \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-Id: demo' \
  -H 'Idempotency-Key: demo-run-001' \
  -d '{
    "agent": "smoke:1",
    "input": {"message": "hello"}
  }'
```

A successful launch returns `202 Accepted`, a job ID, and a `Location` header. Use that ID to poll the job:

```bash
curl -H 'X-Tenant-Id: demo' \
  http://localhost:8080/v1/jobs/JOB_ID
```

Retrying the same request with the same tenant and idempotency key returns the existing job. Use a new key for a new run. A `503` with `submissionOutcome: UNKNOWN` means the launch may still be in progress; poll the returned job ID or retry the same key.

## Agent publishing and production deployment

Agent publishing is not available yet. There is no registry command or upload endpoint for an `AGENT.md` package, and the `agent` field in the request is not resolved against stored instructions. The local task is a smoke container that prints a message and sleeps; it does not run Claude Code, OpenCode, or another agent engine.

Production deployment automation is also not included yet. The supplied deployment path is local Docker Compose with Floci and PostgreSQL. Do not treat the smoke task as a deployed agent. Agent package registration, engine selection, and production AWS deployment will be documented when those flows are implemented.

## Stop the local services

Stop the containers while keeping their data:

```bash
docker compose -f compose.local.yaml down
```

For a deliberate clean reset that also deletes PostgreSQL and Floci data:

```bash
docker compose -f compose.local.yaml down -v
```

The task containers started by ECS are managed by Floci. Stop them through ECS before resetting Floci if they are still running.

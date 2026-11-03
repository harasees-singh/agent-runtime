# Agent Runtime

Agent Runtime accepts a job, records it in PostgreSQL, and starts a dedicated ECS task. Claude Code, OpenCode, and Codex each have a separate runner image and ECS task definition. Select an engine and model for each run.

## Run locally

Requirements: Docker Desktop with Compose, AWS CLI, and network access for the first image builds. Add the credentials for the engine you plan to use to `.env.local`: Claude Code uses `ANTHROPIC_API_KEY`, OpenCode uses its provider key, and Codex uses `CODEX_API_KEY`.

```bash
# Put your provider key in the ignored .env.local file first.
bash scripts/local-up.sh
```

The script starts PostgreSQL and Floci, builds the runner image, registers an ECS task definition, then builds and starts the API. Check the services:

```bash
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:4566/
```

The API is at `http://localhost:8080`; Floci is at `http://localhost:4566`.

## Submit an agent run

`engine` and `model` are optional enums. They default to the configured engine and model in `app/src/main/resources/application.yaml` (Claude Code and `SONNET_4_5` by default). `input.prompt` remains required. The YAML maps each model enum to the provider-specific CLI model ID and each engine to its task definition. For example:

```bash
curl -i -X POST http://localhost:8080/v1/jobs \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-Id: demo' \
  -H 'Idempotency-Key: demo-run-001' \
  -d '{
    "agent": "assistant",
    "engine": "codex",
    "model": "GPT_6_1_SOL",
    "input": {"prompt": "Explain what this agent runtime does."}
  }'
```

The API returns `202 Accepted`, a job ID, and a `Location` header. Poll the job for its state and result:

```bash
curl -H 'X-Tenant-Id: demo' http://localhost:8080/v1/jobs/JOB_ID
```

Use a unique idempotency key for each new run. Retrying the same request with the same key returns the existing job.

The `agent` field is currently a name in the run context; versioned agent packages and `AGENTS.md` publishing are not available yet. The request prompt is sent directly to the CLI in an empty task workspace. Repository checkout and agent tool configuration are not available yet. The local setup builds three images and registers three Floci ECS task definitions; it leaves `.env.local` untouched and stores generated task definitions under `.local/`.

## Stop local services

```bash
docker compose -f compose.local.yaml down
```

Append `-v` to deliberately delete the local PostgreSQL and Floci data volumes. Production deployment and credential delivery are not configured; local provider keys are copied into the Floci task definition for development only.

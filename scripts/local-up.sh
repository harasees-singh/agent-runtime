#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
mkdir -p .local

# Keep .env.local as the operator-managed secret file. It is shell-compatible
# and is sourced only into this local setup process.
if [[ -f .env.local ]]; then
  set -a
  source .env.local
  set +a
fi

docker compose -f compose.local.yaml up -d --wait floci postgres

docker build -t agent-runtime-claude-code:local -f runner/claude-code/Dockerfile .
docker build -t agent-runtime-opencode:local -f runner/opencode/Dockerfile .
docker build -t agent-runtime-codex:local -f runner/codex/Dockerfile .
mvn -q -DskipTests package

awslocal() {
  AWS_ACCESS_KEY_ID=local AWS_SECRET_ACCESS_KEY=local AWS_DEFAULT_REGION=us-east-1 AWS_PAGER= \
    aws --endpoint-url http://localhost:4566 "$@"
}

ready=false
for _ in {1..30}; do
  if awslocal ecs list-clusters >/dev/null 2>&1; then ready=true; break; fi
  sleep 1
done
if [[ "$ready" != true ]]; then echo 'Floci ECS did not become ready' >&2; exit 1; fi

awslocal ecs create-cluster --cluster-name agent-runtime-local >/dev/null

register_engine() {
  local engine="$1" image="$2" secret_names="$3"
  local family="agent-runtime-$engine"
  local definition=".local/task-definition-$engine.json"
  ENGINE="$engine" IMAGE="$image" FAMILY="$family" SECRET_NAMES="$secret_names" \
    python3 - > "$definition" <<'PY'
import json
import os

environment = [{"name": "RUNTIME_ENGINE", "value": os.environ["ENGINE"]}]
for name in os.environ["SECRET_NAMES"].split():
    value = os.environ.get(name)
    if value:
        environment.append({"name": name, "value": value})

print(json.dumps({
    "family": os.environ["FAMILY"],
    "requiresCompatibilities": ["FARGATE"],
    "networkMode": "awsvpc",
    "cpu": "1024",
    "memory": "2048",
    "containerDefinitions": [{
        "name": "runner",
        "image": os.environ["IMAGE"],
        "essential": True,
        "environment": environment,
    }],
}))
PY
  awslocal ecs register-task-definition --cli-input-json "file://$PWD/$definition" \
    --query taskDefinition.taskDefinitionArn --output text
}

claude_task_definition=$(register_engine claude-code agent-runtime-claude-code:local "ANTHROPIC_API_KEY")
opencode_task_definition=$(register_engine opencode agent-runtime-opencode:local \
  "ANTHROPIC_API_KEY OPENAI_API_KEY GOOGLE_API_KEY OPENROUTER_API_KEY")
codex_task_definition=$(register_engine codex agent-runtime-codex:local "CODEX_API_KEY")

cat > .local/compose.env <<EOFVARS
ECS_CLUSTER=agent-runtime-local
CLAUDE_CODE_TASK_DEFINITION=$claude_task_definition
OPENCODE_TASK_DEFINITION=$opencode_task_definition
CODEX_TASK_DEFINITION=$codex_task_definition
ECS_SUBNETS=subnet-default-us-east-1-a
ECS_SECURITY_GROUPS=sg-default-us-east-1
EOFVARS

docker compose --env-file .local/compose.env -f compose.local.yaml up -d --build --wait runtime-service
echo 'Agent Runtime is at http://localhost:8080'

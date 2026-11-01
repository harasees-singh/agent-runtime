#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
docker compose -f compose.local.yaml up -d --wait floci postgres

awslocal() {
  docker run --rm --network agent-runtime-local \
    -e AWS_ACCESS_KEY_ID=local -e AWS_SECRET_ACCESS_KEY=local \
    -e AWS_DEFAULT_REGION=us-east-1 -e AWS_PAGER= \
    amazon/aws-cli:2 --endpoint-url http://floci:4566 "$@"
}

ready=false
for _ in {1..30}; do
  if awslocal ecs list-clusters >/dev/null 2>&1; then ready=true; break; fi
  sleep 1
done
if [[ "$ready" != true ]]; then echo 'Floci ECS did not become ready' >&2; exit 1; fi

awslocal ecs create-cluster --cluster-name agent-runtime-local >/dev/null
vpc_id=$(awslocal ec2 create-vpc --cidr-block 10.42.0.0/16 --query Vpc.VpcId --output text)
subnet_id=$(awslocal ec2 create-subnet --vpc-id "$vpc_id" --cidr-block 10.42.1.0/24 --query Subnet.SubnetId --output text)
security_group_id=$(awslocal ec2 create-security-group --vpc-id "$vpc_id" --group-name runtime-local \
  --description 'Local runner tasks' --query GroupId --output text)
task_definition=$(awslocal ecs register-task-definition \
  --family agent-runtime-runner --requires-compatibilities FARGATE \
  --network-mode awsvpc --cpu 256 --memory 512 \
  --container-definitions '[{"name":"runner","image":"alpine:3.22","essential":true,"command":["sh","-c","echo local-task-started; sleep 300"]}]' \
  --query taskDefinition.taskDefinitionArn --output text)

cat > .env.local <<EOF
ECS_CLUSTER=agent-runtime-local
ECS_TASK_DEFINITION=$task_definition
ECS_CONTAINER=runner
ECS_SUBNETS=$subnet_id
ECS_SECURITY_GROUPS=$security_group_id
EOF

docker compose --env-file .env.local -f compose.local.yaml up -d --build --wait runtime-service
echo 'Agent runtime is at http://localhost:8080'

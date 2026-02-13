#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_CONFIG_FILE="${SCRIPT_DIR}/ecs.env"

CONFIG_FILE="$DEFAULT_CONFIG_FILE"
REGION_OVERRIDE=""
PROFILE_OVERRIDE=""
DRY_RUN=true
FORCE=false
DELETE_IAM=false

info() {
  printf '[INFO] %s\n' "$1"
}

warn() {
  printf '[WARN] %s\n' "$1"
}

error() {
  printf '[ERROR] %s\n' "$1" >&2
}

usage() {
  cat <<'EOF'
Usage:
  ./aws/cleanup-region.sh [--config <path>] [--region <aws-region>] [--profile <aws-profile>] [--execute] [--force] [--delete-iam]

Default behavior is dry-run (no resources are deleted).

Options:
  --config <path>      Config file to load (default: aws/ecs.env)
  --region <region>    Override region from config
  --profile <profile>  Override AWS profile from config
  --execute            Actually perform deletion (instead of dry-run)
  --force              Skip confirmation prompt (only used with --execute)
  --delete-iam         Also delete IAM roles in config (global, not region-scoped)
  -h, --help           Show this help

Examples:
  ./aws/cleanup-region.sh --region eu-west-1
  ./aws/cleanup-region.sh --region eu-west-1 --execute
  ./aws/cleanup-region.sh --config aws/ecs.env --region eu-west-1 --execute --force --delete-iam
EOF
}

require_cmd() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    error "Missing required command: ${cmd}"
    exit 1
  fi
}

parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --config)
        CONFIG_FILE="${2:-}"
        shift 2
        ;;
      --region)
        REGION_OVERRIDE="${2:-}"
        shift 2
        ;;
      --profile)
        PROFILE_OVERRIDE="${2:-}"
        shift 2
        ;;
      --execute)
        DRY_RUN=false
        shift
        ;;
      --force)
        FORCE=true
        shift
        ;;
      --delete-iam)
        DELETE_IAM=true
        shift
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        error "Unknown argument: $1"
        usage
        exit 1
        ;;
    esac
  done
}

aws_cmd() {
  if [ -n "${AWS_PROFILE:-}" ]; then
    aws --profile "$AWS_PROFILE" --region "$AWS_REGION" "$@"
  else
    aws --region "$AWS_REGION" "$@"
  fi
}

try_aws() {
  if [ "$DRY_RUN" = "true" ]; then
    printf '[DRY-RUN] aws'
    if [ -n "${AWS_PROFILE:-}" ]; then
      printf ' --profile %q' "$AWS_PROFILE"
    fi
    printf ' --region %q' "$AWS_REGION"
    for arg in "$@"; do
      printf ' %q' "$arg"
    done
    printf '\n'
    return 0
  fi

  if ! aws_cmd "$@"; then
    warn "Command failed: aws $*"
    return 1
  fi
  return 0
}

load_config() {
  if [ ! -f "$CONFIG_FILE" ]; then
    error "Config file not found: ${CONFIG_FILE}"
    exit 1
  fi

  info "Loading config: ${CONFIG_FILE}"
  # shellcheck disable=SC1090
  set -a
  source "$CONFIG_FILE"
  set +a
}

apply_defaults() {
  AWS_REGION="${AWS_REGION:-eu-west-2}"
  AWS_PROFILE="${AWS_PROFILE:-}"
  ECR_REPOSITORY="${ECR_REPOSITORY:-polyhermes}"
  ECS_CLUSTER_NAME="${ECS_CLUSTER_NAME:-polyhermes-cluster}"
  ECS_SERVICE_NAME="${ECS_SERVICE_NAME:-polyhermes-service}"
  ECS_TASK_FAMILY="${ECS_TASK_FAMILY:-polyhermes-task}"
  CLOUDWATCH_LOG_GROUP="${CLOUDWATCH_LOG_GROUP:-/ecs/polyhermes}"
  SECURITY_GROUP_NAME="${SECURITY_GROUP_NAME:-polyhermes-ecs-sg}"
  SECURITY_GROUP_ID="${SECURITY_GROUP_ID:-}"
  VPC_ID="${VPC_ID:-}"
  ECS_EXECUTION_ROLE_NAME="${ECS_EXECUTION_ROLE_NAME:-polyhermes-ecs-exec-role}"
  ECS_TASK_ROLE_NAME="${ECS_TASK_ROLE_NAME:-polyhermes-ecs-task-role}"

  if [ -n "$REGION_OVERRIDE" ]; then
    AWS_REGION="$REGION_OVERRIDE"
  fi
  if [ -n "$PROFILE_OVERRIDE" ]; then
    AWS_PROFILE="$PROFILE_OVERRIDE"
  fi
}

confirm_execute() {
  if [ "$DRY_RUN" = "true" ]; then
    info "Running in dry-run mode. No resources will be deleted."
    return 0
  fi

  if [ "$FORCE" = "true" ]; then
    return 0
  fi

  printf 'Type the region "%s" to confirm deletion: ' "$AWS_REGION"
  read -r input
  if [ "$input" != "$AWS_REGION" ]; then
    error "Confirmation failed. Aborting."
    exit 1
  fi
}

find_security_group_id() {
  if [ -n "$SECURITY_GROUP_ID" ]; then
    printf '%s' "$SECURITY_GROUP_ID"
    return 0
  fi

  local sg_id
  if [ -n "$VPC_ID" ]; then
    sg_id=$(aws_cmd ec2 describe-security-groups \
      --filters Name=group-name,Values="$SECURITY_GROUP_NAME" Name=vpc-id,Values="$VPC_ID" \
      --query 'SecurityGroups[0].GroupId' \
      --output text 2>/dev/null || true)
  else
    sg_id=$(aws_cmd ec2 describe-security-groups \
      --filters Name=group-name,Values="$SECURITY_GROUP_NAME" \
      --query 'SecurityGroups[0].GroupId' \
      --output text 2>/dev/null || true)
  fi

  if [ "$sg_id" = "None" ]; then
    sg_id=""
  fi
  printf '%s' "$sg_id"
}

cleanup_ecs_service_and_cluster() {
  local cluster_status
  cluster_status=$(aws_cmd ecs describe-clusters \
    --clusters "$ECS_CLUSTER_NAME" \
    --query 'clusters[0].status' \
    --output text 2>/dev/null || true)

  if [ -z "$cluster_status" ] || [ "$cluster_status" = "None" ] || [ "$cluster_status" = "MISSING" ]; then
    info "ECS cluster not found: ${ECS_CLUSTER_NAME}"
    return 0
  fi

  local service_status
  service_status=$(aws_cmd ecs describe-services \
    --cluster "$ECS_CLUSTER_NAME" \
    --services "$ECS_SERVICE_NAME" \
    --query 'services[0].status' \
    --output text 2>/dev/null || true)

  if [ -n "$service_status" ] && [ "$service_status" != "None" ] && [ "$service_status" != "MISSING" ] && [ "$service_status" != "INACTIVE" ]; then
    info "Deleting ECS service: ${ECS_SERVICE_NAME}"
    try_aws ecs update-service \
      --cluster "$ECS_CLUSTER_NAME" \
      --service "$ECS_SERVICE_NAME" \
      --desired-count 0 >/dev/null || true
    try_aws ecs delete-service \
      --cluster "$ECS_CLUSTER_NAME" \
      --service "$ECS_SERVICE_NAME" \
      --force >/dev/null || true

    if [ "$DRY_RUN" = "false" ]; then
      aws_cmd ecs wait services-inactive \
        --cluster "$ECS_CLUSTER_NAME" \
        --services "$ECS_SERVICE_NAME" || true
    fi
  else
    info "ECS service not found or already inactive: ${ECS_SERVICE_NAME}"
  fi

  local tasks
  tasks=$(aws_cmd ecs list-tasks \
    --cluster "$ECS_CLUSTER_NAME" \
    --query 'taskArns[]' \
    --output text 2>/dev/null || true)

  if [ -n "$tasks" ] && [ "$tasks" != "None" ]; then
    info "Stopping remaining tasks in cluster: ${ECS_CLUSTER_NAME}"
    for task_arn in $tasks; do
      try_aws ecs stop-task \
        --cluster "$ECS_CLUSTER_NAME" \
        --task "$task_arn" \
        --reason "cleanup-region.sh" >/dev/null || true
    done
  fi

  info "Deleting ECS cluster: ${ECS_CLUSTER_NAME}"
  try_aws ecs delete-cluster --cluster "$ECS_CLUSTER_NAME" >/dev/null || true
}

cleanup_task_definitions() {
  local task_defs
  task_defs=$(aws_cmd ecs list-task-definitions \
    --family-prefix "$ECS_TASK_FAMILY" \
    --status ACTIVE \
    --query 'taskDefinitionArns[]' \
    --output text 2>/dev/null || true)

  if [ -z "$task_defs" ] || [ "$task_defs" = "None" ]; then
    info "No active task definitions found for family: ${ECS_TASK_FAMILY}"
    return 0
  fi

  info "Deregistering task definitions for family: ${ECS_TASK_FAMILY}"
  for task_def_arn in $task_defs; do
    try_aws ecs deregister-task-definition --task-definition "$task_def_arn" >/dev/null || true
  done
}

cleanup_log_group() {
  local exists
  exists=$(aws_cmd logs describe-log-groups \
    --log-group-name-prefix "$CLOUDWATCH_LOG_GROUP" \
    --query "logGroups[?logGroupName=='${CLOUDWATCH_LOG_GROUP}'].logGroupName | [0]" \
    --output text 2>/dev/null || true)

  if [ -n "$exists" ] && [ "$exists" != "None" ]; then
    info "Deleting CloudWatch log group: ${CLOUDWATCH_LOG_GROUP}"
    try_aws logs delete-log-group --log-group-name "$CLOUDWATCH_LOG_GROUP" >/dev/null || true
  else
    info "CloudWatch log group not found: ${CLOUDWATCH_LOG_GROUP}"
  fi
}

cleanup_ecr_repository() {
  if aws_cmd ecr describe-repositories --repository-names "$ECR_REPOSITORY" >/dev/null 2>&1; then
    info "Deleting ECR repository (force): ${ECR_REPOSITORY}"
    try_aws ecr delete-repository --repository-name "$ECR_REPOSITORY" --force >/dev/null || true
  else
    info "ECR repository not found: ${ECR_REPOSITORY}"
  fi
}

cleanup_security_group() {
  local sg_id
  sg_id=$(find_security_group_id)

  if [ -z "$sg_id" ]; then
    info "Security group not found: ${SECURITY_GROUP_NAME}"
    return 0
  fi

  info "Deleting security group: ${sg_id}"
  try_aws ec2 delete-security-group --group-id "$sg_id" >/dev/null || true
}

cleanup_iam_role() {
  local role_name="$1"
  if ! aws iam get-role --role-name "$role_name" >/dev/null 2>&1; then
    info "IAM role not found: ${role_name}"
    return 0
  fi

  info "Deleting IAM role: ${role_name}"

  local attached_policies
  attached_policies=$(aws iam list-attached-role-policies \
    --role-name "$role_name" \
    --query 'AttachedPolicies[].PolicyArn' \
    --output text 2>/dev/null || true)
  for policy_arn in $attached_policies; do
    if [ -n "$policy_arn" ] && [ "$policy_arn" != "None" ]; then
      if [ "$DRY_RUN" = "true" ]; then
        printf '[DRY-RUN] aws iam detach-role-policy --role-name %q --policy-arn %q\n' "$role_name" "$policy_arn"
      else
        aws iam detach-role-policy --role-name "$role_name" --policy-arn "$policy_arn" || true
      fi
    fi
  done

  local inline_policies
  inline_policies=$(aws iam list-role-policies \
    --role-name "$role_name" \
    --query 'PolicyNames[]' \
    --output text 2>/dev/null || true)
  for policy_name in $inline_policies; do
    if [ -n "$policy_name" ] && [ "$policy_name" != "None" ]; then
      if [ "$DRY_RUN" = "true" ]; then
        printf '[DRY-RUN] aws iam delete-role-policy --role-name %q --policy-name %q\n' "$role_name" "$policy_name"
      else
        aws iam delete-role-policy --role-name "$role_name" --policy-name "$policy_name" || true
      fi
    fi
  done

  if [ "$DRY_RUN" = "true" ]; then
    printf '[DRY-RUN] aws iam delete-role --role-name %q\n' "$role_name"
  else
    aws iam delete-role --role-name "$role_name" || true
  fi
}

main() {
  parse_args "$@"
  require_cmd aws
  require_cmd jq

  load_config
  apply_defaults

  info "Target AWS region: ${AWS_REGION}"
  if [ -n "${AWS_PROFILE:-}" ]; then
    info "Target AWS profile: ${AWS_PROFILE}"
  else
    info "Target AWS profile: <default>"
  fi
  info "Target ECS cluster/service: ${ECS_CLUSTER_NAME}/${ECS_SERVICE_NAME}"

  aws_cmd sts get-caller-identity >/dev/null
  confirm_execute

  cleanup_ecs_service_and_cluster
  cleanup_task_definitions
  cleanup_log_group
  cleanup_ecr_repository
  cleanup_security_group

  if [ "$DELETE_IAM" = "true" ]; then
    warn "Deleting IAM roles (global scope): ${ECS_EXECUTION_ROLE_NAME}, ${ECS_TASK_ROLE_NAME}"
    cleanup_iam_role "$ECS_EXECUTION_ROLE_NAME"
    cleanup_iam_role "$ECS_TASK_ROLE_NAME"
  else
    info "Skipping IAM role cleanup (use --delete-iam to enable)."
  fi

  if [ "$DRY_RUN" = "true" ]; then
    info "Dry-run finished. Re-run with --execute to perform deletion."
  else
    info "Cleanup finished."
  fi
}

main "$@"

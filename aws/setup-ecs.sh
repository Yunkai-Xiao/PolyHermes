#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEFAULT_CONFIG_FILE="${SCRIPT_DIR}/ecs.env"
CONFIG_FILE="${1:-${DEFAULT_CONFIG_FILE}}"

info() {
  printf '[INFO] %s\n' "$1"
}

warn() {
  printf '[WARN] %s\n' "$1"
}

error() {
  printf '[ERROR] %s\n' "$1" >&2
}

require_cmd() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    error "Missing required command: ${cmd}"
    exit 1
  fi
}

aws_cmd() {
  if [ -n "${AWS_PROFILE:-}" ]; then
    aws --profile "$AWS_PROFILE" --region "$AWS_REGION" "$@"
  else
    aws --region "$AWS_REGION" "$@"
  fi
}

extract_github_repo() {
  local remote_url="$1"
  local repo=""

  if [[ "$remote_url" =~ ^https://github\.com/([^/]+/[^/.]+)(\.git)?$ ]]; then
    repo="${BASH_REMATCH[1]}"
  elif [[ "$remote_url" =~ ^git@github\.com:([^/]+/[^/.]+)(\.git)?$ ]]; then
    repo="${BASH_REMATCH[1]}"
  fi

  printf '%s' "$repo"
}

detect_github_repo() {
  local remote url repo
  for remote in fork origin; do
    url=$(git -C "$REPO_ROOT" remote get-url "$remote" 2>/dev/null || true)
    if [ -z "$url" ]; then
      continue
    fi
    repo=$(extract_github_repo "$url")
    if [ -n "$repo" ]; then
      printf '%s' "$repo"
      return 0
    fi
  done
  return 1
}

safe_authorize_ingress() {
  local group_id="$1"
  local protocol="$2"
  local port="$3"
  local cidr="$4"
  local perm_json

  local out
  out=$(mktemp)
  perm_json=$(printf '[{"IpProtocol":"%s","FromPort":%s,"ToPort":%s,"IpRanges":[{"CidrIp":"%s"}]}]' "$protocol" "$port" "$port" "$cidr")
  if aws_cmd ec2 authorize-security-group-ingress \
    --group-id "$group_id" \
    --ip-permissions "$perm_json" >"$out" 2>&1; then
    info "Added ingress rule ${protocol}/${port} from ${cidr}"
  else
    if grep -q "InvalidPermission.Duplicate" "$out"; then
      info "Ingress rule ${protocol}/${port} from ${cidr} already exists"
    else
      cat "$out" >&2
      rm -f "$out"
      error "Failed to add ingress rule ${protocol}/${port} ${cidr}"
      exit 1
    fi
  fi
  rm -f "$out"
}

load_config() {
  if [ ! -f "$CONFIG_FILE" ]; then
    error "Config file not found: ${CONFIG_FILE}"
    error "Create it first: cp ${SCRIPT_DIR}/ecs.env.example ${SCRIPT_DIR}/ecs.env"
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
  SOURCE_MODE="${SOURCE_MODE:-local}"
  SOURCE_IMAGE="${SOURCE_IMAGE:-wrbug/polyhermes:latest}"
  LOCAL_IMAGE_NAME="${LOCAL_IMAGE_NAME:-polyhermes-local}"
  IMAGE_TAG="${IMAGE_TAG:-latest}"

  ECS_CLUSTER_NAME="${ECS_CLUSTER_NAME:-polyhermes-cluster}"
  ECS_SERVICE_NAME="${ECS_SERVICE_NAME:-polyhermes-service}"
  ECS_TASK_FAMILY="${ECS_TASK_FAMILY:-polyhermes-task}"
  ECS_DESIRED_COUNT="${ECS_DESIRED_COUNT:-1}"
  ECS_TASK_CPU="${ECS_TASK_CPU:-1024}"
  ECS_TASK_MEMORY="${ECS_TASK_MEMORY:-2048}"
  ECS_ASSIGN_PUBLIC_IP="${ECS_ASSIGN_PUBLIC_IP:-ENABLED}"

  CLOUDWATCH_LOG_GROUP="${CLOUDWATCH_LOG_GROUP:-/ecs/polyhermes}"
  CLOUDWATCH_LOG_RETENTION_DAYS="${CLOUDWATCH_LOG_RETENTION_DAYS:-14}"

  ECS_EXECUTION_ROLE_NAME="${ECS_EXECUTION_ROLE_NAME:-polyhermes-ecs-exec-role}"
  ECS_TASK_ROLE_NAME="${ECS_TASK_ROLE_NAME:-polyhermes-ecs-task-role}"

  USE_DEFAULT_VPC="${USE_DEFAULT_VPC:-true}"
  VPC_ID="${VPC_ID:-}"
  SUBNET_IDS="${SUBNET_IDS:-}"
  SUBNET_IDS="${SUBNET_IDS// /}"
  SECURITY_GROUP_NAME="${SECURITY_GROUP_NAME:-polyhermes-ecs-sg}"
  SECURITY_GROUP_ID="${SECURITY_GROUP_ID:-}"
  HTTP_CIDR="${HTTP_CIDR:-0.0.0.0/0}"
  HTTPS_CIDR="${HTTPS_CIDR:-0.0.0.0/0}"

  TZ="${TZ:-Europe/London}"
  SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-prod}"
  SERVER_PORT="${SERVER_PORT:-80}"
  DB_URL="${DB_URL:-jdbc:mysql://127.0.0.1:3306/polyhermes?useSSL=false&serverTimezone=UTC&characterEncoding=utf8&allowPublicKeyRetrieval=true}"
  DB_USERNAME="${DB_USERNAME:-root}"
  INITIAL_ADMIN_USERNAME="${INITIAL_ADMIN_USERNAME:-admin}"
  LOG_LEVEL_ROOT="${LOG_LEVEL_ROOT:-WARN}"
  LOG_LEVEL_APP="${LOG_LEVEL_APP:-INFO}"
  ALLOW_PRERELEASE="${ALLOW_PRERELEASE:-false}"
  COPYTRADING_BUY_SHORT_BUFFER_ENABLED="${COPYTRADING_BUY_SHORT_BUFFER_ENABLED:-true}"
  COPYTRADING_BUY_SHORT_BUFFER_WINDOW_MS="${COPYTRADING_BUY_SHORT_BUFFER_WINDOW_MS:-120}"
  if [ -z "${GITHUB_REPO:-}" ]; then
    GITHUB_REPO="$(detect_github_repo || true)"
  fi
  GITHUB_REPO="${GITHUB_REPO:-}"

  MYSQL_IMAGE="${MYSQL_IMAGE:-mysql:8.2}"
  MYSQL_DATABASE="${MYSQL_DATABASE:-polyhermes}"
  MYSQL_CHARACTER_SET_SERVER="${MYSQL_CHARACTER_SET_SERVER:-utf8mb4}"
  MYSQL_COLLATION_SERVER="${MYSQL_COLLATION_SERVER:-utf8mb4_unicode_ci}"
}

validate_config() {
  require_cmd aws
  require_cmd jq
  require_cmd docker

  if [ -z "${DB_PASSWORD:-}" ]; then
    error "DB_PASSWORD is required"
    exit 1
  fi
  if [ -z "${JWT_SECRET:-}" ]; then
    error "JWT_SECRET is required"
    exit 1
  fi
  if [ -z "${ADMIN_RESET_PASSWORD_KEY:-}" ]; then
    error "ADMIN_RESET_PASSWORD_KEY is required"
    exit 1
  fi
  if [ -z "${INITIAL_ADMIN_PASSWORD:-}" ]; then
    error "INITIAL_ADMIN_PASSWORD is required"
    exit 1
  fi
  if [ -z "${GITHUB_REPO:-}" ]; then
    error "GITHUB_REPO is required (expected format: owner/repo)"
    exit 1
  fi

  if [ "$ECS_ASSIGN_PUBLIC_IP" != "ENABLED" ] && [ "$ECS_ASSIGN_PUBLIC_IP" != "DISABLED" ]; then
    error "ECS_ASSIGN_PUBLIC_IP must be ENABLED or DISABLED"
    exit 1
  fi

  if [ "$SOURCE_MODE" != "local" ] && [ "$SOURCE_MODE" != "image" ]; then
    error "SOURCE_MODE must be local or image"
    exit 1
  fi

  case "$ECS_TASK_CPU:$ECS_TASK_MEMORY" in
    256:512|256:1024|256:2048|512:1024|512:2048|512:3072|512:4096|1024:2048|1024:3072|1024:4096|1024:5120|1024:6144|1024:7168|1024:8192|2048:4096|2048:5120|2048:6144|2048:7168|2048:8192|2048:9216|2048:10240|2048:11264|2048:12288|2048:13312|2048:14336|2048:15360|2048:16384|4096:8192|4096:9216|4096:10240|4096:11264|4096:12288|4096:13312|4096:14336|4096:15360|4096:16384|4096:17408|4096:18432|4096:19456|4096:20480|4096:21504|4096:22528|4096:23552|4096:24576|4096:25600|4096:26624|4096:27648|4096:28672|4096:29696|4096:30720)
      ;;
    *)
      error "Invalid Fargate CPU/MEMORY combination: ${ECS_TASK_CPU}/${ECS_TASK_MEMORY}"
      exit 1
      ;;
  esac
}

resolve_account() {
  AWS_ACCOUNT_ID=$(aws_cmd sts get-caller-identity --query 'Account' --output text)
  if [ -z "$AWS_ACCOUNT_ID" ] || [ "$AWS_ACCOUNT_ID" = "None" ]; then
    error "Unable to resolve AWS account id"
    exit 1
  fi
  info "AWS account: ${AWS_ACCOUNT_ID}"
}

resolve_network() {
  if [ -n "$SUBNET_IDS" ]; then
    if [ -z "$VPC_ID" ]; then
      local subnet_array=()
      IFS=',' read -r -a subnet_array <<< "$SUBNET_IDS"
      VPC_ID=$(aws_cmd ec2 describe-subnets \
        --subnet-ids "${subnet_array[@]}" \
        --query 'Subnets[0].VpcId' \
        --output text)
    fi
  else
    if [ -z "$VPC_ID" ] && [ "$USE_DEFAULT_VPC" = "true" ]; then
      VPC_ID=$(aws_cmd ec2 describe-vpcs \
        --filters Name=isDefault,Values=true \
        --query 'Vpcs[0].VpcId' \
        --output text)
      if [ "$VPC_ID" = "None" ]; then
        VPC_ID=""
      fi
    fi

    if [ -z "$VPC_ID" ]; then
      error "No VPC resolved. Set VPC_ID or SUBNET_IDS in config."
      exit 1
    fi

    local discovered
    discovered=$(aws_cmd ec2 describe-subnets \
      --filters Name=vpc-id,Values="$VPC_ID" Name=map-public-ip-on-launch,Values=true \
      --query 'Subnets[].SubnetId' \
      --output text | tr '\t' ',' | sed 's/,$//')

    if [ -z "$discovered" ] || [ "$discovered" = "None" ]; then
      discovered=$(aws_cmd ec2 describe-subnets \
        --filters Name=vpc-id,Values="$VPC_ID" \
        --query 'Subnets[].SubnetId' \
        --output text | tr '\t' ',' | sed 's/,$//')
    fi

    if [ -z "$discovered" ] || [ "$discovered" = "None" ]; then
      error "No subnets found in VPC ${VPC_ID}"
      exit 1
    fi

    SUBNET_IDS="$discovered"
  fi

  if [ -z "$VPC_ID" ] || [ "$VPC_ID" = "None" ]; then
    error "Unable to resolve VPC_ID"
    exit 1
  fi

  if [ -z "$SUBNET_IDS" ]; then
    error "Unable to resolve SUBNET_IDS"
    exit 1
  fi

  info "Network resolved: VPC=${VPC_ID}, SUBNETS=${SUBNET_IDS}"
}

ensure_security_group() {
  if [ -n "$SECURITY_GROUP_ID" ]; then
    info "Using existing security group: ${SECURITY_GROUP_ID}"
  else
    SECURITY_GROUP_ID=$(aws_cmd ec2 describe-security-groups \
      --filters Name=group-name,Values="$SECURITY_GROUP_NAME" Name=vpc-id,Values="$VPC_ID" \
      --query 'SecurityGroups[0].GroupId' \
      --output text)

    if [ "$SECURITY_GROUP_ID" = "None" ] || [ -z "$SECURITY_GROUP_ID" ]; then
      SECURITY_GROUP_ID=$(aws_cmd ec2 create-security-group \
        --group-name "$SECURITY_GROUP_NAME" \
        --description "PolyHermes ECS service security group" \
        --vpc-id "$VPC_ID" \
        --query 'GroupId' \
        --output text)
      info "Created security group: ${SECURITY_GROUP_ID}"
    else
      info "Found security group: ${SECURITY_GROUP_ID}"
    fi
  fi

  safe_authorize_ingress "$SECURITY_GROUP_ID" tcp 80 "$HTTP_CIDR"
  safe_authorize_ingress "$SECURITY_GROUP_ID" tcp 443 "$HTTPS_CIDR"
}

ensure_log_group() {
  local exists
  exists=$(aws_cmd logs describe-log-groups \
    --log-group-name-prefix "$CLOUDWATCH_LOG_GROUP" \
    --query "logGroups[?logGroupName=='${CLOUDWATCH_LOG_GROUP}'].logGroupName" \
    --output text)

  if [ -z "$exists" ]; then
    aws_cmd logs create-log-group --log-group-name "$CLOUDWATCH_LOG_GROUP"
    info "Created log group: ${CLOUDWATCH_LOG_GROUP}"
  else
    info "Log group already exists: ${CLOUDWATCH_LOG_GROUP}"
  fi

  aws_cmd logs put-retention-policy \
    --log-group-name "$CLOUDWATCH_LOG_GROUP" \
    --retention-in-days "$CLOUDWATCH_LOG_RETENTION_DAYS" >/dev/null
}

ensure_ecr_repository() {
  if aws_cmd ecr describe-repositories --repository-names "$ECR_REPOSITORY" >/dev/null 2>&1; then
    info "ECR repository already exists: ${ECR_REPOSITORY}"
  else
    aws_cmd ecr create-repository --repository-name "$ECR_REPOSITORY" >/dev/null
    info "Created ECR repository: ${ECR_REPOSITORY}"
  fi

  ECR_IMAGE_URI="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${ECR_REPOSITORY}:${IMAGE_TAG}"
  info "ECR target image: ${ECR_IMAGE_URI}"
}

push_image_to_ecr() {
  info "Logging into ECR"
  aws_cmd ecr get-login-password | docker login --username AWS --password-stdin "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com" >/dev/null

  local source_ref=""
  if [ "$SOURCE_MODE" = "local" ]; then
    source_ref="${LOCAL_IMAGE_NAME}:${IMAGE_TAG}"
    info "Building local image from repo: ${source_ref}"
    docker build \
      --build-arg VERSION="$IMAGE_TAG" \
      --build-arg GIT_TAG="$IMAGE_TAG" \
      --build-arg GITHUB_REPO_URL="https://github.com/${GITHUB_REPO}" \
      -t "$source_ref" \
      -f "${REPO_ROOT}/Dockerfile" \
      "${REPO_ROOT}" >/dev/null
  else
    source_ref="$SOURCE_IMAGE"
    info "Pulling source image: ${source_ref}"
    docker pull "$source_ref" >/dev/null
  fi

  info "Tagging image"
  docker tag "$source_ref" "$ECR_IMAGE_URI"

  info "Pushing image to ECR"
  docker push "$ECR_IMAGE_URI" >/dev/null
}

ensure_ecs_task_execution_role() {
  if ! aws_cmd iam get-role --role-name "$ECS_EXECUTION_ROLE_NAME" >/dev/null 2>&1; then
    local trust_file
    trust_file=$(mktemp)
    cat > "$trust_file" <<'JSON'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "ecs-tasks.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
JSON
    aws_cmd iam create-role \
      --role-name "$ECS_EXECUTION_ROLE_NAME" \
      --assume-role-policy-document "file://${trust_file}" >/dev/null
    rm -f "$trust_file"
    info "Created ECS execution role: ${ECS_EXECUTION_ROLE_NAME}"
  else
    info "ECS execution role already exists: ${ECS_EXECUTION_ROLE_NAME}"
  fi

  aws_cmd iam attach-role-policy \
    --role-name "$ECS_EXECUTION_ROLE_NAME" \
    --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy >/dev/null || true

  ECS_EXECUTION_ROLE_ARN=$(aws_cmd iam get-role --role-name "$ECS_EXECUTION_ROLE_NAME" --query 'Role.Arn' --output text)
}

ensure_ecs_task_role() {
  if ! aws_cmd iam get-role --role-name "$ECS_TASK_ROLE_NAME" >/dev/null 2>&1; then
    local trust_file
    trust_file=$(mktemp)
    cat > "$trust_file" <<'JSON'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "ecs-tasks.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
JSON
    aws_cmd iam create-role \
      --role-name "$ECS_TASK_ROLE_NAME" \
      --assume-role-policy-document "file://${trust_file}" >/dev/null
    rm -f "$trust_file"
    info "Created ECS task role: ${ECS_TASK_ROLE_NAME}"
  else
    info "ECS task role already exists: ${ECS_TASK_ROLE_NAME}"
  fi

  ECS_TASK_ROLE_ARN=$(aws_cmd iam get-role --role-name "$ECS_TASK_ROLE_NAME" --query 'Role.Arn' --output text)
}

ensure_cluster() {
  local status
  status=$(aws_cmd ecs describe-clusters --clusters "$ECS_CLUSTER_NAME" --query 'clusters[0].status' --output text)
  if [ "$status" = "ACTIVE" ]; then
    info "ECS cluster already exists: ${ECS_CLUSTER_NAME}"
  else
    aws_cmd ecs create-cluster --cluster-name "$ECS_CLUSTER_NAME" >/dev/null
    info "Created ECS cluster: ${ECS_CLUSTER_NAME}"
  fi
}

register_task_definition() {
  local task_def_file
  task_def_file=$(mktemp)

  jq -n \
    --arg family "$ECS_TASK_FAMILY" \
    --arg cpu "$ECS_TASK_CPU" \
    --arg memory "$ECS_TASK_MEMORY" \
    --arg executionRoleArn "$ECS_EXECUTION_ROLE_ARN" \
    --arg taskRoleArn "$ECS_TASK_ROLE_ARN" \
    --arg appImage "$ECR_IMAGE_URI" \
    --arg mysqlImage "$MYSQL_IMAGE" \
    --arg dbPassword "$DB_PASSWORD" \
    --arg mysqlDatabase "$MYSQL_DATABASE" \
    --arg mysqlCharset "$MYSQL_CHARACTER_SET_SERVER" \
    --arg mysqlCollation "$MYSQL_COLLATION_SERVER" \
    --arg tz "$TZ" \
    --arg springProfile "$SPRING_PROFILES_ACTIVE" \
    --arg dbUrl "$DB_URL" \
    --arg dbUser "$DB_USERNAME" \
    --arg jwtSecret "$JWT_SECRET" \
    --arg adminResetPasswordKey "$ADMIN_RESET_PASSWORD_KEY" \
    --arg initialAdminUsername "$INITIAL_ADMIN_USERNAME" \
    --arg initialAdminPassword "$INITIAL_ADMIN_PASSWORD" \
    --arg logLevelRoot "$LOG_LEVEL_ROOT" \
    --arg logLevelApp "$LOG_LEVEL_APP" \
    --arg allowPrerelease "$ALLOW_PRERELEASE" \
    --arg copytradingBuyShortBufferEnabled "$COPYTRADING_BUY_SHORT_BUFFER_ENABLED" \
    --arg copytradingBuyShortBufferWindowMs "$COPYTRADING_BUY_SHORT_BUFFER_WINDOW_MS" \
    --arg githubRepo "$GITHUB_REPO" \
    --arg logGroup "$CLOUDWATCH_LOG_GROUP" \
    --arg region "$AWS_REGION" \
    '{
      family: $family,
      requiresCompatibilities: ["FARGATE"],
      networkMode: "awsvpc",
      cpu: $cpu,
      memory: $memory,
      executionRoleArn: $executionRoleArn,
      taskRoleArn: $taskRoleArn,
      containerDefinitions: [
        {
          name: "mysql",
          image: $mysqlImage,
          essential: true,
          environment: [
            { name: "TZ", value: $tz },
            { name: "MYSQL_ROOT_PASSWORD", value: $dbPassword },
            { name: "MYSQL_DATABASE", value: $mysqlDatabase },
            { name: "MYSQL_CHARACTER_SET_SERVER", value: $mysqlCharset },
            { name: "MYSQL_COLLATION_SERVER", value: $mysqlCollation }
          ],
          healthCheck: {
            command: ["CMD-SHELL", ("mysqladmin ping -h localhost -u root -p" + $dbPassword + " || exit 1")],
            interval: 10,
            timeout: 5,
            retries: 10,
            startPeriod: 30
          },
          logConfiguration: {
            logDriver: "awslogs",
            options: {
              "awslogs-group": $logGroup,
              "awslogs-region": $region,
              "awslogs-stream-prefix": "mysql"
            }
          }
        },
        {
          name: "app",
          image: $appImage,
          essential: true,
          dependsOn: [
            { containerName: "mysql", condition: "HEALTHY" }
          ],
          portMappings: [
            { containerPort: 80, hostPort: 80, protocol: "tcp" }
          ],
          environment: [
            { name: "TZ", value: $tz },
            { name: "SPRING_PROFILES_ACTIVE", value: $springProfile },
            { name: "DB_URL", value: $dbUrl },
            { name: "DB_USERNAME", value: $dbUser },
            { name: "DB_PASSWORD", value: $dbPassword },
            { name: "SERVER_PORT", value: "8000" },
            { name: "JWT_SECRET", value: $jwtSecret },
            { name: "ADMIN_RESET_PASSWORD_KEY", value: $adminResetPasswordKey },
            { name: "INITIAL_ADMIN_USERNAME", value: $initialAdminUsername },
            { name: "INITIAL_ADMIN_PASSWORD", value: $initialAdminPassword },
            { name: "LOG_LEVEL_ROOT", value: $logLevelRoot },
            { name: "LOG_LEVEL_APP", value: $logLevelApp },
            { name: "ALLOW_PRERELEASE", value: $allowPrerelease },
            { name: "COPYTRADING_BUY_SHORT_BUFFER_ENABLED", value: $copytradingBuyShortBufferEnabled },
            { name: "COPYTRADING_BUY_SHORT_BUFFER_WINDOW_MS", value: $copytradingBuyShortBufferWindowMs },
            { name: "GITHUB_REPO", value: $githubRepo }
          ],
          healthCheck: {
            command: ["CMD-SHELL", "curl -f http://localhost/api/system/health || exit 1"],
            interval: 30,
            timeout: 5,
            retries: 5,
            startPeriod: 60
          },
          logConfiguration: {
            logDriver: "awslogs",
            options: {
              "awslogs-group": $logGroup,
              "awslogs-region": $region,
              "awslogs-stream-prefix": "app"
            }
          }
        }
      ]
    }' > "$task_def_file"

  TASK_DEFINITION_ARN=$(aws_cmd ecs register-task-definition \
    --cli-input-json "file://${task_def_file}" \
    --query 'taskDefinition.taskDefinitionArn' \
    --output text)

  rm -f "$task_def_file"

  if [ -z "$TASK_DEFINITION_ARN" ] || [ "$TASK_DEFINITION_ARN" = "None" ]; then
    error "Failed to register task definition"
    exit 1
  fi

  info "Registered task definition: ${TASK_DEFINITION_ARN}"
}

create_or_update_service() {
  local subnets_json
  subnets_json=$(printf '%s' "$SUBNET_IDS" | tr ',' '\n' | sed '/^$/d' | jq -R . | jq -s .)

  local netconf_json
  netconf_json=$(jq -cn \
    --argjson subnets "$subnets_json" \
    --arg sg "$SECURITY_GROUP_ID" \
    --arg assign "$ECS_ASSIGN_PUBLIC_IP" \
    '{awsvpcConfiguration:{subnets:$subnets,securityGroups:[$sg],assignPublicIp:$assign}}')

  local existing_status
  existing_status=$(aws_cmd ecs describe-services \
    --cluster "$ECS_CLUSTER_NAME" \
    --services "$ECS_SERVICE_NAME" \
    --query 'services[0].status' \
    --output text)

  if [ "$existing_status" = "ACTIVE" ]; then
    info "Updating ECS service: ${ECS_SERVICE_NAME}"
    aws_cmd ecs update-service \
      --cluster "$ECS_CLUSTER_NAME" \
      --service "$ECS_SERVICE_NAME" \
      --task-definition "$TASK_DEFINITION_ARN" \
      --desired-count "$ECS_DESIRED_COUNT" >/dev/null
  else
    info "Creating ECS service: ${ECS_SERVICE_NAME}"
    aws_cmd ecs create-service \
      --cluster "$ECS_CLUSTER_NAME" \
      --service-name "$ECS_SERVICE_NAME" \
      --task-definition "$TASK_DEFINITION_ARN" \
      --desired-count "$ECS_DESIRED_COUNT" \
      --launch-type FARGATE \
      --network-configuration "$netconf_json" >/dev/null
  fi

  info "Waiting for service to become stable..."
  aws_cmd ecs wait services-stable --cluster "$ECS_CLUSTER_NAME" --services "$ECS_SERVICE_NAME"
}

get_service_public_ip() {
  local task_arn
  task_arn=$(aws_cmd ecs list-tasks \
    --cluster "$ECS_CLUSTER_NAME" \
    --service-name "$ECS_SERVICE_NAME" \
    --desired-status RUNNING \
    --query 'taskArns[0]' \
    --output text)

  if [ -z "$task_arn" ] || [ "$task_arn" = "None" ]; then
    warn "No running task found"
    SERVICE_PUBLIC_IP=""
    return
  fi

  local eni_id
  eni_id=$(aws_cmd ecs describe-tasks \
    --cluster "$ECS_CLUSTER_NAME" \
    --tasks "$task_arn" \
    --query 'tasks[0].attachments[0].details[?name==`networkInterfaceId`].value | [0]' \
    --output text)

  if [ -z "$eni_id" ] || [ "$eni_id" = "None" ]; then
    warn "No ENI found for task"
    SERVICE_PUBLIC_IP=""
    return
  fi

  SERVICE_PUBLIC_IP=$(aws_cmd ec2 describe-network-interfaces \
    --network-interface-ids "$eni_id" \
    --query 'NetworkInterfaces[0].Association.PublicIp' \
    --output text)

  if [ "$SERVICE_PUBLIC_IP" = "None" ]; then
    SERVICE_PUBLIC_IP=""
  fi
}

print_summary() {
  cat <<EOF_SUMMARY

ECS deployment complete.

AWS Region:            ${AWS_REGION}
Cluster:               ${ECS_CLUSTER_NAME}
Service:               ${ECS_SERVICE_NAME}
Task Definition:       ${TASK_DEFINITION_ARN}
ECR Image:             ${ECR_IMAGE_URI}
Security Group ID:     ${SECURITY_GROUP_ID}
Subnets:               ${SUBNET_IDS}
CloudWatch Log Group:  ${CLOUDWATCH_LOG_GROUP}
EOF_SUMMARY

  if [ -n "${SERVICE_PUBLIC_IP:-}" ]; then
    cat <<EOF_IP
Public URL (HTTP):     http://${SERVICE_PUBLIC_IP}
Health URL:            http://${SERVICE_PUBLIC_IP}/api/system/health
EOF_IP
  else
    cat <<'EOF_NO_IP'
Public IP not resolved yet. Check running tasks and ENI associations:
  aws ecs list-tasks --cluster <cluster> --service-name <service>
EOF_NO_IP
  fi

  cat <<EOF_HINTS

Useful commands:
  aws --profile ${AWS_PROFILE:-<default>} --region ${AWS_REGION} ecs describe-services --cluster ${ECS_CLUSTER_NAME} --services ${ECS_SERVICE_NAME}
  aws --profile ${AWS_PROFILE:-<default>} --region ${AWS_REGION} logs tail ${CLOUDWATCH_LOG_GROUP} --since 30m --follow

Important note:
  This setup runs MySQL inside the ECS task. Storage is ephemeral on Fargate.
  If the task is replaced/restarted, DB data may be lost.
  For production persistence, move MySQL to RDS (or use EFS-backed design).
EOF_HINTS
}

main() {
  load_config
  apply_defaults
  validate_config

  info "Checking AWS credentials..."
  aws_cmd sts get-caller-identity >/dev/null

  resolve_account
  resolve_network
  ensure_security_group
  ensure_log_group
  ensure_ecr_repository
  push_image_to_ecr
  ensure_ecs_task_execution_role
  ensure_ecs_task_role
  ensure_cluster

  # IAM propagation delay safety
  sleep 8

  register_task_definition
  create_or_update_service
  get_service_public_ip
  print_summary
}

main "$@"

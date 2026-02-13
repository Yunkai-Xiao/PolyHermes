#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEFAULT_CONFIG_FILE="${SCRIPT_DIR}/ec2.env"
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
    error "Create it first: cp ${SCRIPT_DIR}/ec2.env.example ${SCRIPT_DIR}/ec2.env"
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

  INSTANCE_NAME="${INSTANCE_NAME:-polyhermes-ec2}"
  INSTANCE_TYPE="${INSTANCE_TYPE:-t3.micro}"
  ROOT_VOLUME_GB="${ROOT_VOLUME_GB:-30}"
  AMI_ID="${AMI_ID:-}"
  AMI_SSM_PARAMETER="${AMI_SSM_PARAMETER:-/aws/service/canonical/ubuntu/server/22.04/stable/current/amd64/hvm/ebs-gp2/ami-id}"

  USE_DEFAULT_VPC="${USE_DEFAULT_VPC:-true}"
  VPC_ID="${VPC_ID:-}"
  SUBNET_ID="${SUBNET_ID:-}"

  SECURITY_GROUP_NAME="${SECURITY_GROUP_NAME:-polyhermes-ec2-sg}"
  SECURITY_GROUP_ID="${SECURITY_GROUP_ID:-}"
  HTTP_CIDR="${HTTP_CIDR:-0.0.0.0/0}"
  HTTPS_CIDR="${HTTPS_CIDR:-0.0.0.0/0}"

  SSH_ENABLED="${SSH_ENABLED:-false}"
  SSH_CIDR="${SSH_CIDR:-}"
  CREATE_KEY_PAIR="${CREATE_KEY_PAIR:-false}"
  KEY_PAIR_NAME="${KEY_PAIR_NAME:-polyhermes-eu-west-2}"
  KEY_PAIR_SAVE_PATH="${KEY_PAIR_SAVE_PATH:-${SCRIPT_DIR}}"

  IAM_ROLE_NAME="${IAM_ROLE_NAME:-polyhermes-ec2-role}"
  INSTANCE_PROFILE_NAME="${INSTANCE_PROFILE_NAME:-polyhermes-ec2-profile}"

  ASSIGN_ELASTIC_IP="${ASSIGN_ELASTIC_IP:-false}"
  EIP_ALLOCATION_ID="${EIP_ALLOCATION_ID:-}"

  DEPLOY_REF="${DEPLOY_REF:-main}"
  if [ -z "${GITHUB_REPO:-}" ]; then
    GITHUB_REPO="$(detect_github_repo || true)"
  fi
  GITHUB_REPO="${GITHUB_REPO:-}"
  IMAGE_TAG="${IMAGE_TAG:-latest}"

  TZ="${TZ:-Europe/London}"
  SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-prod}"
  SERVER_PORT="${SERVER_PORT:-80}"
  MYSQL_PORT="${MYSQL_PORT:-3307}"
  DB_URL="${DB_URL:-jdbc:mysql://mysql:3306/polyhermes?useSSL=false&serverTimezone=UTC&characterEncoding=utf8&allowPublicKeyRetrieval=true}"
  DB_USERNAME="${DB_USERNAME:-root}"

  INITIAL_ADMIN_USERNAME="${INITIAL_ADMIN_USERNAME:-admin}"
  LOG_LEVEL_ROOT="${LOG_LEVEL_ROOT:-WARN}"
  LOG_LEVEL_APP="${LOG_LEVEL_APP:-INFO}"
  ALLOW_PRERELEASE="${ALLOW_PRERELEASE:-false}"
}

validate_config() {
  require_cmd aws

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

  if [ "$SSH_ENABLED" = "true" ] && [ -z "$SSH_CIDR" ]; then
    error "SSH_ENABLED=true requires SSH_CIDR"
    exit 1
  fi

  if [ "$SSH_ENABLED" = "true" ] && [ -z "$KEY_PAIR_NAME" ]; then
    error "SSH_ENABLED=true requires KEY_PAIR_NAME"
    exit 1
  fi

  if [ "$ROOT_VOLUME_GB" -lt 16 ]; then
    error "ROOT_VOLUME_GB must be at least 16"
    exit 1
  fi
}

resolve_network() {
  if [ -n "$SUBNET_ID" ]; then
    VPC_ID=$(aws_cmd ec2 describe-subnets \
      --subnet-ids "$SUBNET_ID" \
      --query 'Subnets[0].VpcId' \
      --output text)
    if [ "$VPC_ID" = "None" ] || [ -z "$VPC_ID" ]; then
      error "Unable to resolve VPC from SUBNET_ID=${SUBNET_ID}"
      exit 1
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
      error "No VPC resolved. Set VPC_ID or SUBNET_ID in config."
      exit 1
    fi

    SUBNET_ID=$(aws_cmd ec2 describe-subnets \
      --filters Name=vpc-id,Values="$VPC_ID" Name=map-public-ip-on-launch,Values=true \
      --query 'Subnets[0].SubnetId' \
      --output text)

    if [ "$SUBNET_ID" = "None" ] || [ -z "$SUBNET_ID" ]; then
      SUBNET_ID=$(aws_cmd ec2 describe-subnets \
        --filters Name=vpc-id,Values="$VPC_ID" \
        --query 'Subnets[0].SubnetId' \
        --output text)
    fi

    if [ "$SUBNET_ID" = "None" ] || [ -z "$SUBNET_ID" ]; then
      error "No subnet found in VPC ${VPC_ID}"
      exit 1
    fi
  fi

  info "Network resolved: VPC=${VPC_ID}, SUBNET=${SUBNET_ID}"
}

resolve_ami() {
  if [ -n "$AMI_ID" ]; then
    info "Using AMI from config: ${AMI_ID}"
    return
  fi

  local candidate_params=()
  candidate_params+=("$AMI_SSM_PARAMETER")
  candidate_params+=("/aws/service/canonical/ubuntu/server/22.04/stable/current/amd64/hvm/ebs-gp2/ami-id")
  candidate_params+=("/aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id")
  candidate_params+=("/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-6.1-x86_64")

  local param
  for param in "${candidate_params[@]}"; do
    if [ -z "$param" ]; then
      continue
    fi

    AMI_ID=$(aws_cmd ssm get-parameter \
      --name "$param" \
      --query 'Parameter.Value' \
      --output text 2>/dev/null || true)

    if [ -n "$AMI_ID" ] && [ "$AMI_ID" != "None" ]; then
      info "Resolved AMI via SSM: ${AMI_ID} (${param})"
      return
    fi
  done

  AMI_ID=$(aws_cmd ec2 describe-images \
    --owners 099720109477 \
    --filters \
      "Name=name,Values=ubuntu/images/hvm-ssd/ubuntu-jammy-22.04-amd64-server-*" \
      "Name=architecture,Values=x86_64" \
      "Name=virtualization-type,Values=hvm" \
      "Name=root-device-type,Values=ebs" \
      "Name=state,Values=available" \
    --query 'sort_by(Images,&CreationDate)[-1].ImageId' \
    --output text 2>/dev/null || true)

  if [ -n "$AMI_ID" ] && [ "$AMI_ID" != "None" ]; then
    info "Resolved AMI via EC2 image lookup: ${AMI_ID}"
    return
  fi

  error "Could not resolve AMI from SSM or EC2 image lookup. Set AMI_ID manually in config."
  exit 1
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
        --description "PolyHermes EC2 security group" \
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

  if [ "$SSH_ENABLED" = "true" ]; then
    safe_authorize_ingress "$SECURITY_GROUP_ID" tcp 22 "$SSH_CIDR"
  fi
}

ensure_key_pair() {
  if [ "$SSH_ENABLED" != "true" ]; then
    info "SSH disabled; skipping key pair setup"
    return
  fi

  local existing
  existing=$(aws_cmd ec2 describe-key-pairs \
    --key-names "$KEY_PAIR_NAME" \
    --query 'KeyPairs[0].KeyName' \
    --output text 2>/dev/null || true)

  if [ "$CREATE_KEY_PAIR" = "true" ]; then
    mkdir -p "$KEY_PAIR_SAVE_PATH"
    local key_path="${KEY_PAIR_SAVE_PATH%/}/${KEY_PAIR_NAME}.pem"

    if [ "$existing" = "$KEY_PAIR_NAME" ]; then
      warn "Key pair ${KEY_PAIR_NAME} already exists in AWS; not creating a new one"
      if [ -f "$key_path" ]; then
        chmod 400 "$key_path"
        info "Existing local key file found: ${key_path}"
      else
        warn "Local key file not found at ${key_path}; you must provide your existing private key manually"
      fi
    else
      aws_cmd ec2 create-key-pair \
        --key-name "$KEY_PAIR_NAME" \
        --query 'KeyMaterial' \
        --output text > "$key_path"
      chmod 400 "$key_path"
      info "Created key pair and saved private key to: ${key_path}"
    fi
  else
    if [ "$existing" != "$KEY_PAIR_NAME" ]; then
      error "SSH is enabled but key pair ${KEY_PAIR_NAME} does not exist. Set CREATE_KEY_PAIR=true or provide an existing key pair name."
      exit 1
    fi
    info "Using existing key pair: ${KEY_PAIR_NAME}"
  fi
}

ensure_iam_profile() {
  if ! aws_cmd iam get-role --role-name "$IAM_ROLE_NAME" >/dev/null 2>&1; then
    local trust_file
    trust_file=$(mktemp)
    cat > "$trust_file" <<'JSON'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "ec2.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
JSON
    aws_cmd iam create-role \
      --role-name "$IAM_ROLE_NAME" \
      --assume-role-policy-document "file://${trust_file}" >/dev/null
    rm -f "$trust_file"
    info "Created IAM role: ${IAM_ROLE_NAME}"
  else
    info "IAM role already exists: ${IAM_ROLE_NAME}"
  fi

  aws_cmd iam attach-role-policy \
    --role-name "$IAM_ROLE_NAME" \
    --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore >/dev/null || true

  if ! aws_cmd iam get-instance-profile --instance-profile-name "$INSTANCE_PROFILE_NAME" >/dev/null 2>&1; then
    aws_cmd iam create-instance-profile --instance-profile-name "$INSTANCE_PROFILE_NAME" >/dev/null
    info "Created instance profile: ${INSTANCE_PROFILE_NAME}"
    sleep 2
  else
    info "Instance profile already exists: ${INSTANCE_PROFILE_NAME}"
  fi

  local attached
  attached=$(aws_cmd iam get-instance-profile \
    --instance-profile-name "$INSTANCE_PROFILE_NAME" \
    --query "InstanceProfile.Roles[?RoleName=='${IAM_ROLE_NAME}'].RoleName" \
    --output text)

  if [ -z "$attached" ]; then
    aws_cmd iam add-role-to-instance-profile \
      --instance-profile-name "$INSTANCE_PROFILE_NAME" \
      --role-name "$IAM_ROLE_NAME" >/dev/null
    info "Attached role ${IAM_ROLE_NAME} to instance profile ${INSTANCE_PROFILE_NAME}"
    sleep 10
  else
    info "Role ${IAM_ROLE_NAME} already attached to instance profile ${INSTANCE_PROFILE_NAME}"
  fi
}

build_app_env_b64() {
  local app_env
  app_env=$(cat <<EOF_ENV
DB_URL=${DB_URL}
DB_USERNAME=${DB_USERNAME}
DB_PASSWORD=${DB_PASSWORD}
SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE}
SERVER_PORT=${SERVER_PORT}
MYSQL_PORT=${MYSQL_PORT}
JWT_SECRET=${JWT_SECRET}
ADMIN_RESET_PASSWORD_KEY=${ADMIN_RESET_PASSWORD_KEY}
INITIAL_ADMIN_USERNAME=${INITIAL_ADMIN_USERNAME}
INITIAL_ADMIN_PASSWORD=${INITIAL_ADMIN_PASSWORD}
LOG_LEVEL_ROOT=${LOG_LEVEL_ROOT}
LOG_LEVEL_APP=${LOG_LEVEL_APP}
ALLOW_PRERELEASE=${ALLOW_PRERELEASE}
GITHUB_REPO=${GITHUB_REPO}
TZ=${TZ}
EOF_ENV
)

  printf '%s' "$app_env" | base64 | tr -d '\n'
}

run_instance() {
  local app_env_b64
  app_env_b64=$(build_app_env_b64)

  local user_data
  user_data=$(cat <<EOF_USER_DATA
#!/bin/bash
set -euxo pipefail
exec > >(tee -a /var/log/polyhermes-bootstrap.log) 2>&1

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y docker.io curl jq
if ! docker compose version >/dev/null 2>&1; then
  apt-get install -y docker-compose-plugin || true
fi
if ! docker compose version >/dev/null 2>&1; then
  apt-get install -y docker-compose || true
fi
if ! docker compose version >/dev/null 2>&1 && ! command -v docker-compose >/dev/null 2>&1; then
  echo "docker compose is not available after package install" >&2
  exit 1
fi
systemctl enable --now docker

compose_cmd() {
  if docker compose version >/dev/null 2>&1; then
    docker compose "\$@"
  else
    docker-compose "\$@"
  fi
}

mkdir -p /opt/polyhermes
cat > /opt/polyhermes/.env.b64 <<'B64EOF'
${app_env_b64}
B64EOF
base64 -d /opt/polyhermes/.env.b64 > /opt/polyhermes/.env
rm -f /opt/polyhermes/.env.b64

curl -fsSL "https://raw.githubusercontent.com/${GITHUB_REPO}/${DEPLOY_REF}/docker-compose.prod.yml" -o /opt/polyhermes/docker-compose.prod.yml

if [ "${IMAGE_TAG}" != "latest" ]; then
  sed -i "s|wrbug/polyhermes:latest|wrbug/polyhermes:${IMAGE_TAG}|g" /opt/polyhermes/docker-compose.prod.yml
fi

cd /opt/polyhermes
compose_cmd -f docker-compose.prod.yml pull
compose_cmd -f docker-compose.prod.yml up -d
compose_cmd -f docker-compose.prod.yml ps
EOF_USER_DATA
)

  local run_args
  run_args=(
    ec2 run-instances
    --image-id "$AMI_ID"
    --instance-type "$INSTANCE_TYPE"
    --subnet-id "$SUBNET_ID"
    --security-group-ids "$SECURITY_GROUP_ID"
    --iam-instance-profile "Name=${INSTANCE_PROFILE_NAME}"
    --associate-public-ip-address
    --user-data "$user_data"
    --metadata-options 'HttpTokens=required,HttpEndpoint=enabled'
    --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=${INSTANCE_NAME}},{Key=Project,Value=PolyHermes}]"
    --tag-specifications "ResourceType=volume,Tags=[{Key=Name,Value=${INSTANCE_NAME}-root},{Key=Project,Value=PolyHermes}]"
    --block-device-mappings "[{\"DeviceName\":\"/dev/sda1\",\"Ebs\":{\"VolumeSize\":${ROOT_VOLUME_GB},\"VolumeType\":\"gp3\",\"DeleteOnTermination\":true}}]"
    --query 'Instances[0].InstanceId'
    --output text
  )

  if [ "$SSH_ENABLED" = "true" ]; then
    run_args+=(--key-name "$KEY_PAIR_NAME")
  fi

  INSTANCE_ID=$(aws_cmd "${run_args[@]}")
  if [ -z "$INSTANCE_ID" ] || [ "$INSTANCE_ID" = "None" ]; then
    error "Failed to create EC2 instance"
    exit 1
  fi

  info "Created instance: ${INSTANCE_ID}"

  info "Waiting for instance to enter running state..."
  aws_cmd ec2 wait instance-running --instance-ids "$INSTANCE_ID"

  info "Waiting for instance status checks..."
  aws_cmd ec2 wait instance-status-ok --instance-ids "$INSTANCE_ID"

  if [ "$ASSIGN_ELASTIC_IP" = "true" ]; then
    if [ -z "$EIP_ALLOCATION_ID" ]; then
      EIP_ALLOCATION_ID=$(aws_cmd ec2 allocate-address --domain vpc --query 'AllocationId' --output text)
      info "Allocated Elastic IP: ${EIP_ALLOCATION_ID}"
    fi

    aws_cmd ec2 associate-address \
      --instance-id "$INSTANCE_ID" \
      --allocation-id "$EIP_ALLOCATION_ID" >/dev/null

    info "Associated Elastic IP allocation: ${EIP_ALLOCATION_ID}"
  fi
}

print_summary() {
  local public_ip
  public_ip=$(aws_cmd ec2 describe-instances \
    --instance-ids "$INSTANCE_ID" \
    --query 'Reservations[0].Instances[0].PublicIpAddress' \
    --output text)

  local public_dns
  public_dns=$(aws_cmd ec2 describe-instances \
    --instance-ids "$INSTANCE_ID" \
    --query 'Reservations[0].Instances[0].PublicDnsName' \
    --output text)

  local ssm_cmd
  if [ -n "$AWS_PROFILE" ]; then
    ssm_cmd="aws --profile ${AWS_PROFILE} --region ${AWS_REGION} ssm start-session --target ${INSTANCE_ID}"
  else
    ssm_cmd="aws --region ${AWS_REGION} ssm start-session --target ${INSTANCE_ID}"
  fi

  cat <<EOF_SUMMARY

Setup complete.

AWS Region:        ${AWS_REGION}
Instance ID:       ${INSTANCE_ID}
Instance Name:     ${INSTANCE_NAME}
Public IP:         ${public_ip}
Public DNS:        ${public_dns}
Security Group ID: ${SECURITY_GROUP_ID}
Subnet ID:         ${SUBNET_ID}
VPC ID:            ${VPC_ID}

App URL (HTTP):    http://${public_ip}

SSM session:
  ${ssm_cmd}

Cloud-init log on EC2:
  /var/log/polyhermes-bootstrap.log
EOF_SUMMARY

  if [ "$SSH_ENABLED" = "true" ]; then
    cat <<EOF_SSH

SSH (if enabled):
  ssh -i ${KEY_PAIR_SAVE_PATH%/}/${KEY_PAIR_NAME}.pem ubuntu@${public_ip}
EOF_SSH
  fi
}

main() {
  load_config
  apply_defaults
  validate_config

  info "Checking AWS credentials..."
  aws_cmd sts get-caller-identity >/dev/null

  resolve_network
  resolve_ami
  ensure_security_group
  ensure_key_pair
  ensure_iam_profile
  run_instance
  print_summary
}

main "$@"

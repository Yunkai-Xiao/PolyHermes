#!/bin/bash

# PolyHermes 一体化部署脚本
# 安全策略：
# 1) 默认不删除数据卷
# 2) 部署前可自动备份数据库
# 3) 清空数据必须显式确认 CONFIRM_DB_WIPE=YES

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

DEFAULT_JWT_SECRET="change-me-in-production"
DEFAULT_ADMIN_RESET_KEY="change-me-in-production"

info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

compose() {
    if command -v docker-compose &> /dev/null; then
        docker-compose "$@"
    else
        docker compose "$@"
    fi
}

check_docker() {
    if ! command -v docker &> /dev/null; then
        error "Docker 未安装，请先安装 Docker"
        exit 1
    fi
    if command -v docker-compose &> /dev/null; then
        :
    elif docker compose version &> /dev/null; then
        :
    else
        error "Docker Compose 未安装，请先安装 Docker Compose"
        exit 1
    fi
    info "Docker 环境检查通过"
}

generate_random_string() {
    local length=${1:-32}
    openssl rand -hex "$length" 2>/dev/null || \
    cat /dev/urandom | tr -dc 'a-zA-Z0-9' | fold -w "$length" | head -n 1
}

read_env_value() {
    local key="$1"
    if [ ! -f ".env" ]; then
        echo ""
        return
    fi
    grep "^${key}=" .env 2>/dev/null | tail -n 1 | cut -d'=' -f2- | sed 's/^"//;s/"$//' || true
}

ensure_data_dirs() {
    local mysql_data_dir
    mysql_data_dir="$(read_env_value MYSQL_DATA_DIR)"
    if [ -z "$mysql_data_dir" ]; then
        mysql_data_dir="./data/mysql"
    fi
    mkdir -p "$mysql_data_dir"
    mkdir -p "./backups"
}

create_env_file() {
    if [ -f ".env" ]; then
        return
    fi

    warn ".env 文件不存在，创建示例文件..."

    local db_password jwt_secret admin_reset_key initial_admin_password
    db_password=$(generate_random_string 32)
    jwt_secret=$(generate_random_string 64)
    admin_reset_key=$(generate_random_string 32)
    initial_admin_password=$(generate_random_string 12)

    cat > .env <<EOF
# 数据库配置
DB_URL=jdbc:mysql://mysql:3306/polyhermes?useSSL=false&serverTimezone=UTC&characterEncoding=utf8&allowPublicKeyRetrieval=true
DB_USERNAME=root
DB_PASSWORD=${db_password}

# Spring Profile
SPRING_PROFILES_ACTIVE=prod

# 服务器端口（对外暴露的端口）
SERVER_PORT=80

# MySQL 端口（可选，用于外部连接，默认 3307 避免与本地 MySQL 冲突）
MYSQL_PORT=3307

# MySQL 数据目录（宿主机路径，避免 down -v 误删）
MYSQL_DATA_DIR=./data/mysql

# JWT 密钥（已自动生成随机值，生产环境建议修改）
JWT_SECRET=${jwt_secret}

# 管理员密码重置密钥（已自动生成随机值，生产环境建议修改）
ADMIN_RESET_PASSWORD_KEY=${admin_reset_key}

# 初始管理员账户（仅数据库首次初始化时生效）
INITIAL_ADMIN_USERNAME=admin
INITIAL_ADMIN_PASSWORD=${initial_admin_password}

# 是否在每次部署前自动备份（推荐 true）
AUTO_BACKUP_BEFORE_DEPLOY=true

# 日志级别配置（可选，默认值：root=WARN, app=INFO）
# 可选值：TRACE, DEBUG, INFO, WARN, ERROR, OFF
# LOG_LEVEL_ROOT=WARN
# LOG_LEVEL_APP=INFO
EOF

    info ".env 文件已创建（含随机密钥、重置密钥与初始管理员密码）"
    warn "请保存以下关键信息（来自 .env）："
    warn "  - ADMIN_RESET_PASSWORD_KEY=${admin_reset_key}"
    warn "  - INITIAL_ADMIN_USERNAME=admin"
    warn "  - INITIAL_ADMIN_PASSWORD=${initial_admin_password}"
}

append_env_if_missing() {
    local key="$1"
    local value="$2"
    if ! grep -q "^${key}=" .env 2>/dev/null; then
        echo "${key}=${value}" >> .env
        info "已向 .env 添加 ${key}"
    fi
}

ensure_env_defaults() {
    if [ ! -f ".env" ]; then
        return
    fi

    local generated_initial_admin_password
    generated_initial_admin_password=$(generate_random_string 12)

    append_env_if_missing "MYSQL_DATA_DIR" "./data/mysql"
    append_env_if_missing "INITIAL_ADMIN_USERNAME" "admin"
    append_env_if_missing "INITIAL_ADMIN_PASSWORD" "${generated_initial_admin_password}"
    append_env_if_missing "AUTO_BACKUP_BEFORE_DEPLOY" "true"
}

check_security_config() {
    local jwt_secret admin_reset_key initial_admin_username initial_admin_password
    jwt_secret=$(read_env_value JWT_SECRET)
    admin_reset_key=$(read_env_value ADMIN_RESET_PASSWORD_KEY)
    initial_admin_username=$(read_env_value INITIAL_ADMIN_USERNAME)
    initial_admin_password=$(read_env_value INITIAL_ADMIN_PASSWORD)

    if [ -n "${JWT_SECRET:-}" ]; then
        jwt_secret="$JWT_SECRET"
    fi
    if [ -n "${ADMIN_RESET_PASSWORD_KEY:-}" ]; then
        admin_reset_key="$ADMIN_RESET_PASSWORD_KEY"
    fi
    if [ -n "${INITIAL_ADMIN_USERNAME:-}" ]; then
        initial_admin_username="$INITIAL_ADMIN_USERNAME"
    fi
    if [ -n "${INITIAL_ADMIN_PASSWORD:-}" ]; then
        initial_admin_password="$INITIAL_ADMIN_PASSWORD"
    fi

    local errors=0
    if [ -z "$jwt_secret" ] || [ "$jwt_secret" = "$DEFAULT_JWT_SECRET" ]; then
        error "JWT_SECRET 不能为空且不能使用默认值 '${DEFAULT_JWT_SECRET}'"
        errors=$((errors + 1))
    fi
    if [ -z "$admin_reset_key" ] || [ "$admin_reset_key" = "$DEFAULT_ADMIN_RESET_KEY" ]; then
        error "ADMIN_RESET_PASSWORD_KEY 不能为空且不能使用默认值 '${DEFAULT_ADMIN_RESET_KEY}'"
        errors=$((errors + 1))
    fi
    if [ -z "$initial_admin_username" ]; then
        error "INITIAL_ADMIN_USERNAME 不能为空"
        errors=$((errors + 1))
    fi
    if [ -z "$initial_admin_password" ] || [ "${#initial_admin_password}" -lt 6 ]; then
        error "INITIAL_ADMIN_PASSWORD 至少 6 位"
        errors=$((errors + 1))
    fi

    if [ "$errors" -gt 0 ]; then
        echo ""
        error "安全配置检查失败，部署已取消"
        echo ""
        info "提示：可以使用以下命令生成随机密钥："
        info "  openssl rand -hex 32  # ADMIN_RESET_PASSWORD_KEY"
        info "  openssl rand -hex 64  # JWT_SECRET"
        exit 1
    fi

    info "安全配置检查通过"
}

mysql_running() {
    compose ps mysql 2>/dev/null | grep -q "Up"
}

backup_database() {
    ensure_data_dirs
    local ts backup_file
    ts=$(date +%Y%m%d-%H%M%S)
    backup_file="./backups/polyhermes-${ts}.sql"

    if ! mysql_running; then
        warn "MySQL 容器未运行，跳过数据库备份"
        return 0
    fi

    info "正在备份数据库到: ${backup_file}"
    if compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --quick --set-gtid-purged=OFF polyhermes' > "$backup_file"; then
        if [ -s "$backup_file" ]; then
            info "数据库备份完成: ${backup_file}"
        else
            rm -f "$backup_file"
            warn "数据库备份结果为空，已丢弃该备份文件"
            return 1
        fi
    else
        rm -f "$backup_file"
        warn "数据库备份失败，继续执行后续流程"
        return 1
    fi
}

show_reset_key() {
    local reset_key
    reset_key=$(read_env_value ADMIN_RESET_PASSWORD_KEY)
    info "重置密钥（来自 .env）:"
    info "  ADMIN_RESET_PASSWORD_KEY: ${reset_key:-<未设置>}"
}

show_admin_credentials() {
    local initial_admin_username initial_admin_password
    initial_admin_username=$(read_env_value INITIAL_ADMIN_USERNAME)
    initial_admin_password=$(read_env_value INITIAL_ADMIN_PASSWORD)
    info "初始管理员信息（来自 .env，仅数据库首次初始化时生效）:"
    info "  用户名: ${initial_admin_username:-<未设置>}"
    info "  密码: ${initial_admin_password:-<未设置>}"
}

deploy() {
    check_security_config
    ensure_data_dirs

    local auto_backup
    auto_backup=$(read_env_value AUTO_BACKUP_BEFORE_DEPLOY)
    if [ -n "${AUTO_BACKUP_BEFORE_DEPLOY:-}" ]; then
        auto_backup="$AUTO_BACKUP_BEFORE_DEPLOY"
    fi
    if [ -z "$auto_backup" ]; then
        auto_backup="true"
    fi

    if [ "$auto_backup" = "true" ]; then
        backup_database || true
    fi

    if [ "${USE_DOCKER_HUB:-false}" = "true" ]; then
        info "使用 Docker Hub 镜像（推荐生产环境）..."
        info "拉取最新镜像..."
        docker pull wrbug/polyhermes:latest || warn "拉取镜像失败，将继续使用本地镜像/构建"
        warn "请确保 docker-compose.yml 中已配置使用 image: wrbug/polyhermes:latest"
    else
        local current_branch docker_version
        current_branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "dev")
        docker_version=$(echo "$current_branch" | tr '/' '-')

        info "构建 Docker 镜像（本地构建，版本号: ${docker_version}）..."
        mkdir -p backend/build/libs
        export VERSION=${docker_version}
        export GIT_TAG=${docker_version}
        export GITHUB_REPO_URL=https://github.com/WrBug/PolyHermes
        compose build
    fi

    info "启动服务..."
    compose up -d

    info "等待服务启动..."
    sleep 5

    info "检查服务状态..."
    compose ps
}

stop_services() {
    info "停止服务（保留数据）..."
    compose down
    info "服务已停止"
}

wipe_data() {
    local mysql_data_dir
    mysql_data_dir=$(read_env_value MYSQL_DATA_DIR)
    if [ -z "$mysql_data_dir" ]; then
        mysql_data_dir="./data/mysql"
    fi

    if [ "${CONFIRM_DB_WIPE:-}" != "YES" ]; then
        error "危险操作已拦截：清空数据需要显式确认"
        error "请使用: CONFIRM_DB_WIPE=YES ./deploy.sh wipe-data"
        exit 1
    fi

    warn "即将清空数据库数据目录: ${mysql_data_dir}"
    backup_database || true
    compose down --remove-orphans || true

    if [ -d "$mysql_data_dir" ]; then
        rm -rf "$mysql_data_dir"
    fi
    mkdir -p "$mysql_data_dir"

    info "数据库数据已清空"
}

show_usage() {
    cat <<EOF
用法: ./deploy.sh [deploy|backup|stop|status|logs|show-reset-key|show-admin|wipe-data] [--use-docker-hub]

命令:
  deploy      构建并启动（默认）
  backup      立即备份数据库到 ./backups
  stop        停止服务（保留数据）
  status      查看服务状态
  logs        跟随查看日志
  show-reset-key  显示 .env 中的 ADMIN_RESET_PASSWORD_KEY
  show-admin  显示 .env 中的初始管理员用户名/密码
  wipe-data   清空数据库数据（必须 CONFIRM_DB_WIPE=YES）

示例:
  ./deploy.sh
  ./deploy.sh backup
  ./deploy.sh show-reset-key
  ./deploy.sh --use-docker-hub
  CONFIRM_DB_WIPE=YES ./deploy.sh wipe-data
EOF
}

main() {
    echo "=========================================="
    echo "  PolyHermes 一体化部署脚本"
    echo "=========================================="
    echo ""

    local mode="deploy"
    export USE_DOCKER_HUB=false

    for arg in "$@"; do
        case "$arg" in
            --use-docker-hub|-d)
                export USE_DOCKER_HUB=true
                ;;
            deploy|backup|stop|status|logs|show-reset-key|show-admin|wipe-data)
                mode="$arg"
                ;;
            -h|--help|help)
                show_usage
                exit 0
                ;;
            *)
                error "未知参数: $arg"
                show_usage
                exit 1
                ;;
        esac
    done

    check_docker
    create_env_file
    ensure_env_defaults

    case "$mode" in
        deploy)
            if [ "$USE_DOCKER_HUB" = "true" ]; then
                info "将使用 Docker Hub 镜像（生产环境推荐）"
            fi
            deploy
            echo ""
            info "部署完成！"
            info "访问地址: http://localhost:${SERVER_PORT:-80}"
            info "查看日志: $(command -v docker-compose >/dev/null && echo 'docker-compose logs -f' || echo 'docker compose logs -f')"
            info "停止服务: $(command -v docker-compose >/dev/null && echo 'docker-compose down' || echo 'docker compose down')"
            show_reset_key
            show_admin_credentials
            ;;
        backup)
            backup_database
            ;;
        stop)
            stop_services
            ;;
        status)
            compose ps
            ;;
        logs)
            compose logs -f
            ;;
        show-reset-key)
            show_reset_key
            ;;
        show-admin)
            show_admin_credentials
            ;;
        wipe-data)
            wipe_data
            ;;
    esac
}

main "$@"

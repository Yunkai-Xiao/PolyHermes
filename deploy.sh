#!/bin/bash

# PolyHermes 一体化部署脚本
# 将前后端一起部署到一个 Docker 容器中

set -e

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 打印信息
info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查 Docker 环境
check_docker() {
    if ! command -v docker &> /dev/null; then
        error "Docker 未安装，请先安装 Docker"
        exit 1
    fi
    
    if ! command -v docker-compose &> /dev/null; then
        error "Docker Compose 未安装，请先安装 Docker Compose"
        exit 1
    fi
    
    info "Docker 环境检查通过"
}

# 生成随机字符串
generate_random_string() {
    local length=${1:-32}
    openssl rand -hex $length 2>/dev/null || \
    cat /dev/urandom | tr -dc 'a-zA-Z0-9' | fold -w $length | head -n 1
}

# 创建 .env 文件（如果不存在）
create_env_file() {
    if [ ! -f ".env" ]; then
        warn ".env 文件不存在，创建示例文件..."
        
        # 生成随机值
        DB_PASSWORD=$(generate_random_string 32)
        JWT_SECRET=$(generate_random_string 64)
        ADMIN_RESET_KEY=$(generate_random_string 32)
        
        cat > .env <<EOF
# 数据库配置
DB_URL=jdbc:mysql://mysql:3306/polyhermes?useSSL=false&serverTimezone=UTC&characterEncoding=utf8&allowPublicKeyRetrieval=true
DB_USERNAME=root
DB_PASSWORD=${DB_PASSWORD}

# Spring Profile
SPRING_PROFILES_ACTIVE=prod

# 服务器端口（对外暴露的端口）
SERVER_PORT=80

# MySQL 端口（可选，用于外部连接，默认 3307 避免与本地 MySQL 冲突）
MYSQL_PORT=3307

# Polygon RPC
POLYGON_RPC_URL=https://polygon-rpc.com

# JWT 密钥（已自动生成随机值，生产环境建议修改）
JWT_SECRET=${JWT_SECRET}

# 管理员密码重置密钥（已自动生成随机值，生产环境建议修改）
ADMIN_RESET_PASSWORD_KEY=${ADMIN_RESET_KEY}
EOF
        info ".env 文件已创建，已自动生成随机密码和密钥"
        warn "生产环境建议修改以下参数："
        warn "  - DB_PASSWORD: 数据库密码（当前: ${DB_PASSWORD:0:8}...）"
        warn "  - JWT_SECRET: JWT 密钥（当前: ${JWT_SECRET:0:8}...）"
        warn "  - ADMIN_RESET_PASSWORD_KEY: 管理员密码重置密钥（当前: ${ADMIN_RESET_KEY:0:8}...）"
        exit 1
    fi
}

# 构建并启动
deploy() {
    info "构建 Docker 镜像..."
    docker-compose build
    
    info "启动服务..."
    docker-compose up -d
    
    info "等待服务启动..."
    sleep 5
    
    info "检查服务状态..."
    docker-compose ps
    
    info "查看日志: docker-compose logs -f"
    info "停止服务: docker-compose down"
}

# 主函数
main() {
    echo "=========================================="
    echo "  PolyHermes 一体化部署脚本"
    echo "=========================================="
    echo ""
    
    check_docker
    create_env_file
    deploy
    
    echo ""
    info "部署完成！"
    info "访问地址: http://localhost:${SERVER_PORT:-80}"
}

main "$@"


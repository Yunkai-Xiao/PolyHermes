#!/bin/bash

# PolyHermes 前端构建脚本
# 支持自定义后端 API 地址

set -e

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 默认配置
DEFAULT_API_URL="http://127.0.0.1:8000"
API_URL="${VITE_API_URL:-$DEFAULT_API_URL}"

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

# 检查 Node.js 环境
check_node() {
    if ! command -v node &> /dev/null; then
        error "Node.js 未安装，请先安装 Node.js 18+"
        exit 1
    fi
    
    NODE_VERSION=$(node -v | cut -d'v' -f2 | cut -d'.' -f1)
    if [ "$NODE_VERSION" -lt 18 ]; then
        error "Node.js 版本过低，需要 Node.js 18+，当前版本: $(node -v)"
        exit 1
    fi
    
    info "Node.js 环境检查通过: $(node -v)"
}

# 创建环境配置文件
create_env_file() {
    info "创建环境配置文件..."
    
    # 解析 API URL，提取协议、主机和端口
    if [[ $API_URL == http* ]]; then
        # 提取协议和主机
        PROTOCOL=$(echo $API_URL | sed -E 's|^([^:]+)://.*|\1|')
        HOST_PORT=$(echo $API_URL | sed -E 's|^[^:]+://([^/]+).*|\1|')
        HOST=$(echo $HOST_PORT | cut -d':' -f1)
        PORT=$(echo $HOST_PORT | cut -d':' -f2)
        
        # 构建 WebSocket URL
        if [ "$PROTOCOL" = "https" ]; then
            WS_PROTOCOL="wss"
        else
            WS_PROTOCOL="ws"
        fi
        WS_URL="${WS_PROTOCOL}://${HOST}:${PORT}"
    else
        error "API URL 格式错误，应为 http://host:port 或 https://host:port"
        exit 1
    fi
    
    # 创建 .env.production 文件
    cat > .env.production <<EOF
# 后端 API 地址
VITE_API_URL=$API_URL
VITE_WS_URL=$WS_URL
EOF
    
    info "环境配置已创建: .env.production"
    info "  API URL: $API_URL"
    info "  WebSocket URL: $WS_URL"
}

# 构建应用
build_app() {
    info "开始构建前端应用..."
    
    # 检查依赖
    if [ ! -d "node_modules" ]; then
        info "安装依赖..."
        npm install
    fi
    
    # 构建
    info "执行构建..."
    npm run build
    
    if [ ! -d "dist" ]; then
        error "构建失败，dist 目录不存在"
        exit 1
    fi
    
    info "构建完成: dist/"
    info "构建产物大小: $(du -sh dist | cut -f1)"
}

# 主函数
main() {
    echo "=========================================="
    echo "  PolyHermes 前端构建脚本"
    echo "=========================================="
    echo ""
    
    check_node
    
    # 解析参数
    if [ "$1" = "--api-url" ] && [ -n "$2" ]; then
        API_URL="$2"
        info "使用自定义 API 地址: $API_URL"
    elif [ -n "$VITE_API_URL" ]; then
        info "使用环境变量 API 地址: $API_URL"
    else
        info "使用默认 API 地址: $API_URL"
        warn "提示: 可通过环境变量 VITE_API_URL 或参数 --api-url 自定义后端地址"
    fi
    
    create_env_file
    build_app
    
    echo ""
    info "构建完成！"
    info "部署方式："
    info "  1. 静态文件服务器：将 dist/ 目录部署到 Nginx、Apache 等"
    info "  2. 使用 serve 预览：npx serve -s dist -l 3000"
}

main "$@"


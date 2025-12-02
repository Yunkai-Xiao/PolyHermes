import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => {
  // 加载环境变量
  const env = loadEnv(mode, process.cwd(), '')
  
  // 从环境变量获取 API 地址，默认使用 localhost:8000
  const API_URL = env.VITE_API_URL || 'http://localhost:8000'
  const WS_URL = env.VITE_WS_URL || 'ws://localhost:8000'
  
  return {
    plugins: [react()],
    server: {
      port: 3000,
      proxy: {
        '/api': {
          target: API_URL,
          changeOrigin: true,
          rewrite: (path) => path.replace(/^\/api/, '/api')
        },
        '/ws': {
          target: WS_URL,
          ws: true,
          changeOrigin: true
        }
      }
    },
    // 定义环境变量，在构建时注入
    define: {
      'import.meta.env.VITE_API_URL': JSON.stringify(API_URL),
      'import.meta.env.VITE_WS_URL': JSON.stringify(WS_URL)
    }
  }
})


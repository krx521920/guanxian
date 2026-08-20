import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')

  return {
    plugins: [vue()],
    server: {
      port: 5173,
      proxy: env.VITE_API_PROXY
        ? {
            '/api': {
              target: env.VITE_API_PROXY,
              changeOrigin: true,
            },
          }
        : undefined,
    },
  }
})

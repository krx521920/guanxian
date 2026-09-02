import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const demoProxyCredentials: Record<string, string | undefined> = {
    SYSTEM_ADMIN: env.DEV_API_PROXY_AUTH_SYSTEM_ADMIN,
    ASSOCIATION_ADMIN: env.DEV_API_PROXY_AUTH_ASSOCIATION_ADMIN,
    ASSOCIATION_OPERATOR: env.DEV_API_PROXY_AUTH_ASSOCIATION_OPERATOR,
    ENTERPRISE_ADMIN: env.DEV_API_PROXY_AUTH_ENTERPRISE_ADMIN,
    ENTERPRISE_MEMBER: env.DEV_API_PROXY_AUTH_ENTERPRISE_MEMBER,
  }
  const demoProxyEnabled = Object.values(demoProxyCredentials).some(Boolean)

  return {
    plugins: [vue()],
    server: {
      port: 5173,
      proxy: env.VITE_API_PROXY
        ? {
            '/api': {
              target: env.VITE_API_PROXY,
              changeOrigin: true,
              configure: demoProxyEnabled
                ? (proxy) => {
                    proxy.on('proxyReq', (proxyRequest, request) => {
                      const rawRole = request.headers['x-guanxian-demo-role']
                      const role = Array.isArray(rawRole) ? rawRole[0] : rawRole
                      const credentials = role ? demoProxyCredentials[role] : undefined
                      proxyRequest.removeHeader('x-guanxian-demo-role')
                      if (credentials) {
                        proxyRequest.setHeader(
                          'authorization',
                          `Basic ${Buffer.from(credentials, 'utf8').toString('base64')}`,
                        )
                      }
                    })
                  }
                : undefined,
            },
          }
        : undefined,
    },
  }
})

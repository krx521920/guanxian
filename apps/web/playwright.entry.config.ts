import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './tests/ui',
  testMatch: ['entry-routing.spec.ts', 'enterprise-onboarding.spec.ts'],
  outputDir: '../../test-results/entry-routing',
  workers: 1,
  timeout: 30000,
  use: {
    ...devices['Desktop Chrome'],
    channel: process.env.GUANXIAN_BROWSER_CHANNEL,
    baseURL: 'http://127.0.0.1:18188',
    locale: 'zh-CN',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 18188 --strictPort',
    url: 'http://127.0.0.1:18188/public',
    // These tests use the real OIDC code path with mocked identity/business responses.
    // They do not contact a real identity provider or the production database.
    env: {
      VITE_AUTH_MODE: 'oidc',
      VITE_OIDC_AUTHORITY: 'http://127.0.0.1:18188/identity/realms/entry-tests',
      VITE_OIDC_CLIENT_ID: 'entry-tests',
      VITE_OIDC_REDIRECT_URI: 'http://127.0.0.1:18188/auth/callback',
      VITE_OIDC_POST_LOGOUT_REDIRECT_URI: 'http://127.0.0.1:18188/login',
      VITE_API_BASE_URL: '/api/v1',
      VITE_API_PROXY: '',
    },
    reuseExistingServer: false,
    timeout: 60000,
  },
})

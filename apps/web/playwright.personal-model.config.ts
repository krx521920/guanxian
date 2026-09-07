import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './tests/ui',
  testMatch: ['personal-model.spec.ts', 'assistant-output.spec.ts', 'assistant-business-results.spec.ts', 'assistant-followup.spec.ts', 'assistant-fit-check.spec.ts', 'assistant-workspace.spec.ts'],
  outputDir: '../../test-results/personal-model-ui',
  workers: 1,
  timeout: 30000,
  use: { ...devices['Desktop Chrome'], channel: process.env.GUANXIAN_BROWSER_CHANNEL, baseURL: 'http://127.0.0.1:18186', locale: 'zh-CN', screenshot: 'only-on-failure', trace: 'retain-on-failure' },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 18186 --strictPort',
    url: 'http://127.0.0.1:18186/tests/ui/model-settings.html',
    env: { VITE_AUTH_MODE: 'demo' },
    // Opt in only after verifying this port belongs to this worktree's local demo Vite server.
    reuseExistingServer: process.env.GUANXIAN_REUSE_LOCAL_PREVIEW === '1',
    timeout: 60000,
  },
})

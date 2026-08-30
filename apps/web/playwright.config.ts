import { defineConfig, devices } from '@playwright/test'

const webBaseUrl = process.env.E2E_WEB_BASE_URL?.trim() || 'http://127.0.0.1:18082'

export default defineConfig({
  testDir: './tests/e2e',
  outputDir: '../../test-results/browser-e2e/artifacts',
  fullyParallel: false,
  workers: 1,
  // The suite persists workflow state in real dependencies. Retrying a partial
  // run can mask non-idempotent failures, so CI must report the first failure.
  retries: 0,
  timeout: 240_000,
  expect: { timeout: 15_000 },
  reporter: process.env.CI
    ? [['line'], ['html', { outputFolder: '../../test-results/browser-e2e/html', open: 'never' }]]
    : [['list'], ['html', { outputFolder: '../../test-results/browser-e2e/html', open: 'never' }]],
  use: {
    ...devices['Desktop Chrome'],
    baseURL: webBaseUrl,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
  },
})

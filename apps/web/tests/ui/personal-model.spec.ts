import { expect, test, type Page } from '@playwright/test'
import type { ModelSettings } from '../../src/services/personal-model'

function initialSettings(): ModelSettings {
  return { storageAvailable: true, egressAllowed: true, saved: null, providers: [
    { id: 'DOUBAO', label: '豆包 · 火山方舟', endpoint: 'https://ark.cn-beijing.volces.com/api/v3/chat/completions', modelHint: '填写方舟模型或接入点 ID', documentationUrl: 'https://www.volcengine.com/docs/82379/1330626' },
    { id: 'DEEPSEEK', label: 'DeepSeek', endpoint: 'https://api.deepseek.com/chat/completions', modelHint: '填写模型 ID', documentationUrl: 'https://api-docs.deepseek.com/' },
    { id: 'KIMI', label: 'Kimi', endpoint: 'https://api.moonshot.cn/v1/chat/completions', modelHint: '填写模型 ID', documentationUrl: 'https://platform.kimi.com/docs/get-api-key' },
    { id: 'QWEN', label: '千问 · 阿里云百炼（北京）', endpoint: 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions', modelHint: 'qwen-plus', documentationUrl: 'https://help.aliyun.com/zh/model-studio/' },
  ] }
}

async function fixture(page: Page, egressAllowed = true) {
  const settings = initialSettings()
  settings.egressAllowed = egressAllowed
  const saves: Record<string, unknown>[] = []
  const tests: Record<string, unknown>[] = []
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    let data: unknown = { items: [], total: 0 }
    if (path.endsWith('/assistant/model-settings/test')) {
      tests.push(route.request().postDataJSON())
      data = { success: true, message: '连接成功；仅验证流式文本响应。', latencyMs: 123 }
    } else if (path.endsWith('/assistant/model-settings')) {
      if (method === 'PUT') {
        const body = route.request().postDataJSON()
        saves.push(body)
        settings.saved = { provider: body.provider, model: body.model, enabled: body.enabled, hasKey: true, revision: String(saves.length) }
      }
      if (method === 'DELETE') settings.saved = null
      data = method === 'DELETE' ? true : settings
    }
    await route.fulfill({ json: { code: 'OK', data } })
  })
  await page.goto('/tests/ui/model-settings.html')
  await page.getByRole('button', { name: '打开个人模型接入' }).click()
  await expect(page.getByLabel('模型厂商', { exact: true })).toBeVisible()
  return { settings, saves, tests }
}

test('dock is bottom-left above chat; save, test, retain key, switch provider, delete', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  const state = await fixture(page)
  const modelDock = await page.getByRole('button', { name: '打开个人模型接入' }).boundingBox()
  const chatDock = await page.getByRole('button', { name: '打开管线智能助手' }).boundingBox()
  expect(modelDock!.x).toBeLessThan(50)
  expect(modelDock!.y + modelDock!.height).toBeLessThan(chatDock!.y)
  await page.getByLabel('模型厂商', { exact: true }).selectOption('DEEPSEEK')
  await page.getByLabel('模型 ID', { exact: true }).fill('test-model')
  await page.locator('#personal-model-key').fill('test-only-key-do-not-use')
  await expect(page.getByRole('button', { name: '保存设置' })).toBeDisabled()
  await page.getByRole('checkbox', { name: /我了解并同意/ }).check()
  await page.getByRole('button', { name: '保存设置' }).click()
  await expect(page.getByText('已安全保存')).toBeVisible()
  await expect(page.locator('#personal-model-key')).toHaveValue('')
  expect(state.saves[0].apiKey).toBe('test-only-key-do-not-use')
  expect(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))).not.toContain('test-only-key')
  await page.getByRole('button', { name: '测试连接', exact: true }).click()
  await expect(page.getByText(/测试首个文本响应：123 ms/)).toBeVisible()
  expect(state.tests).toEqual([{ externalDataConsent: true }])
  await page.screenshot({ path: '../../test-results/personal-model-ui/desktop.png', fullPage: true })
  await page.getByLabel('模型 ID', { exact: true }).fill('another-model')
  await expect(page.getByRole('button', { name: '测试连接', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '保存设置' }).click()
  await expect(page.getByText('已安全保存')).toBeVisible()
  expect(state.saves[1].apiKey).toBe('')
  await page.getByLabel('模型厂商', { exact: true }).selectOption('KIMI')
  await expect(page.locator('#personal-model-key')).toHaveValue('')
  await expect(page.getByRole('checkbox', { name: /我了解并同意/ })).not.toBeChecked()
  await expect(page.getByRole('button', { name: '保存设置' })).toBeDisabled()
  await page.getByRole('button', { name: '删除配置', exact: true }).click()
  await page.getByRole('button', { name: '确认删除', exact: true }).click()
  await expect(page.getByText(/个人配置与密钥已删除/)).toBeVisible()
  expect(state.settings.saved).toBeNull()
  await page.getByRole('button', { name: '关闭模型接入' }).click()
  await expect(page.getByRole('button', { name: '打开个人模型接入' })).toBeFocused()
})

test('platform egress restriction is visible and prevents testing', async ({ page }) => {
  const state = await fixture(page, false)
  await expect(page.getByText(/平台尚未允许模型数据外发/)).toBeVisible()
  await expect(page.getByRole('button', { name: '测试连接', exact: true })).toBeDisabled()
  expect(state.tests).toHaveLength(0)
})

test('mobile dialog fits and closes with Escape without retaining unsaved key', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await fixture(page)
  await page.locator('#personal-model-key').fill('unsaved-test-only-key')
  const box = await page.getByRole('dialog', { name: '模型接入' }).boundingBox()
  expect(box!.x).toBeGreaterThanOrEqual(0)
  expect(box!.x + box!.width).toBeLessThanOrEqual(390)
  expect(box!.y + box!.height).toBeLessThanOrEqual(844)
  await page.locator('#personal-model-key').fill('')
  await page.screenshot({ path: '../../test-results/personal-model-ui/mobile.png', fullPage: true })
  await page.locator('#personal-model-key').fill('unsaved-test-only-key')
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog', { name: '模型接入' })).toHaveCount(0)
  await page.getByRole('button', { name: '打开个人模型接入' }).click()
  await expect(page.locator('#personal-model-key')).toHaveValue('')
})

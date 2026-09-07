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

async function fixture(page: Page, egressAllowed = true, storageAvailable = true, saved: ModelSettings['saved'] = null) {
  const settings = initialSettings()
  settings.egressAllowed = egressAllowed
  settings.storageAvailable = storageAvailable
  settings.saved = saved
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
  await page.getByRole('button', { name: '打开管线智能助手' }).click()
  await page.getByRole('button', { name: '打开个人模型接入' }).click()
  await expect(page.getByLabel('模型厂商', { exact: true })).toBeVisible()
  expect(saves).toHaveLength(0)
  expect(tests).toHaveLength(0)
  return { settings, saves, tests }
}

test('model icon belongs to the composer; save, test, retain key, switch provider, delete', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  const state = await fixture(page)
  const modelDock = await page.getByRole('button', { name: '打开个人模型接入' }).boundingBox()
  const chatDock = await page.getByRole('button', { name: '关闭管线智能助手' }).boundingBox()
  expect(modelDock!.x).toBeGreaterThan(900)
  expect(chatDock!.x + chatDock!.width).toBeGreaterThan(1380)
  await expect(page.locator('.model-access-launcher')).toHaveCount(0)
  expect(modelDock!.y + modelDock!.height).toBeLessThan(chatDock!.y)
  await page.getByLabel('模型厂商', { exact: true }).selectOption('DEEPSEEK')
  await page.getByLabel('模型 ID', { exact: true }).fill('test-model')
  await page.locator('#personal-model-key').fill('test-only-key-do-not-use')
  await expect(page.getByRole('dialog', { name: '模型接入' }).getByRole('checkbox')).toHaveCount(0)
  await expect(page.getByText(/保存即启用并同意/)).toBeVisible()
  await expect(page.getByRole('button', { name: '保存并启用' })).toBeEnabled()
  await page.getByRole('button', { name: '保存并启用' }).click()
  await expect(page.getByText('已安全保存')).toBeVisible()
  await expect(page.locator('#personal-model-key')).toHaveValue('')
  await expect(page.locator('.assistant-model-icon .model-monogram')).toHaveText('D')
  expect(state.saves[0].apiKey).toBe('test-only-key-do-not-use')
  expect(state.saves[0].enabled).toBe(true)
  expect(state.saves[0].externalDataConsent).toBe(true)
  const dialogBox = await page.getByRole('dialog', { name: '模型接入' }).boundingBox()
  expect(dialogBox!.width).toBeLessThanOrEqual(480)
  expect(dialogBox!.height).toBeLessThanOrEqual(680)
  expect(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))).not.toContain('test-only-key')
  await page.getByRole('button', { name: '测试连接', exact: true }).click()
  await expect(page.getByText(/测试首个文本响应：123 ms/)).toBeVisible()
  expect(state.tests).toEqual([{ externalDataConsent: true }])
  await page.screenshot({ path: '../../test-results/personal-model-ui/desktop.png', fullPage: true })
  await page.getByLabel('模型 ID', { exact: true }).fill('another-model')
  await expect(page.getByRole('button', { name: '测试连接', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '保存并启用' }).click()
  await expect(page.getByText('已安全保存')).toBeVisible()
  expect(state.saves[1].apiKey).toBe('')
  await page.getByLabel('模型厂商', { exact: true }).selectOption('KIMI')
  await expect(page.locator('#personal-model-key')).toHaveValue('')
  await expect(page.getByRole('button', { name: '保存并启用' })).toBeDisabled()
  await page.getByLabel('模型 ID', { exact: true }).fill('kimi-test-model')
  await page.locator('#personal-model-key').fill('kimi-test-only-key')
  await page.getByRole('button', { name: '保存并启用' }).click()
  await expect(page.getByRole('button', { name: '测试连接', exact: true })).toBeEnabled()
  expect(state.saves[2]).toMatchObject({ provider: 'KIMI', enabled: true, externalDataConsent: true })
  await page.getByRole('button', { name: '删除配置', exact: true }).click()
  await page.getByRole('button', { name: '确认删除', exact: true }).click()
  await expect(page.getByText(/个人配置与密钥已删除/)).toBeVisible()
  expect(state.settings.saved).toBeNull()
  await page.getByRole('button', { name: '关闭模型接入' }).click()
  await expect(page.getByRole('button', { name: '打开个人模型接入' })).toBeFocused()
  await expect(page.locator('.assistant-model-icon .model-monogram')).toHaveCount(0)
})

test('platform egress restriction prevents testing and never claims a saved setting is callable', async ({ page }) => {
  const state = await fixture(page, false)
  await expect(page.getByText(/模型网络访问未开启/)).toBeVisible()
  await expect(page.getByRole('button', { name: '测试连接', exact: true })).toBeDisabled()
  expect(state.tests).toHaveLength(0)
  await page.getByLabel('模型 ID', { exact: true }).fill('test-model')
  await page.locator('#personal-model-key').fill('test-only-key-do-not-use')
  await page.getByRole('button', { name: '保存配置' }).click()
  await expect(page.getByText(/当前仍使用本地模式/)).toBeVisible()
  await expect(page.getByRole('button', { name: '测试连接', exact: true })).toBeDisabled()
  expect(state.tests).toHaveLength(0)
})

test('missing encryption and egress show one actionable warning and never save a key', async ({ page }) => {
  const state = await fixture(page, false, false)
  await expect(page.locator('.model-warning')).toHaveCount(1)
  await expect(page.getByText(/需要配置密钥加密和模型网络访问/)).toBeVisible()
  await expect(page.locator('#personal-model-key')).toBeDisabled()
  await expect(page.getByRole('button', { name: '保存配置' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '测试连接', exact: true })).toBeDisabled()
  expect(state.saves).toHaveLength(0)
  expect(state.tests).toHaveLength(0)
})

test('opening a disabled configuration never silently enables or calls it; saving enables explicitly', async ({ page }) => {
  const state = await fixture(page, true, true, { provider: 'DEEPSEEK', model: 'test-model', enabled: false, hasKey: true, revision: 'old' })
  await expect(page.getByRole('button', { name: '测试连接', exact: true })).toBeDisabled()
  expect(state.settings.saved!.enabled).toBe(false)
  await page.getByRole('button', { name: '保存并启用' }).click()
  await expect(page.getByRole('button', { name: '测试连接', exact: true })).toBeEnabled()
  expect(state.saves[0]).toMatchObject({ enabled: true, externalDataConsent: true, apiKey: '' })
  expect(state.tests).toHaveLength(0)
})

test('short and narrow viewport keeps close and save actions on screen', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 480 })
  await fixture(page, false, false)
  for (const locator of [page.getByRole('dialog', { name: '模型接入' }), page.getByRole('button', { name: '关闭模型接入' }), page.getByRole('button', { name: '保存配置' })]) {
    const box = await locator.boundingBox()
    expect(box!.x).toBeGreaterThanOrEqual(0)
    expect(box!.y).toBeGreaterThanOrEqual(0)
    expect(box!.x + box!.width).toBeLessThanOrEqual(320)
    expect(box!.y + box!.height).toBeLessThanOrEqual(480)
  }
  expect(await page.locator('.model-settings-body').evaluate(node => node.scrollWidth <= node.clientWidth)).toBe(true)
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

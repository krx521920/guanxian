import { afterEach, describe, expect, it, vi } from 'vitest'
import { modelSettingsError, personalModelApi, personalModelLabel, type ModelSettings } from './personal-model'
import { request } from './http'

vi.mock('./http', () => ({ request: vi.fn() }))
afterEach(() => vi.clearAllMocks())
const settings: ModelSettings = { providers: [{ id: 'KIMI', label: 'Kimi', endpoint: 'https://api.moonshot.cn/v1/chat/completions', modelHint: '', documentationUrl: '' }], storageAvailable: true, egressAllowed: true,
  saved: { provider: 'KIMI', model: 'test-model', hasKey: true, enabled: true, revision: 'revision-a' } }

describe('personal model API and state', () => {
  it('never sends a client-selected owner or destination', async () => {
    await personalModelApi.save({ provider: 'KIMI', model: 'test-model', apiKey: 'test-only-key', enabled: true, externalDataConsent: true })
    const [url, options] = vi.mocked(request).mock.calls[0]
    expect(url).toBe('/assistant/model-settings')
    expect(options?.method).toBe('PUT')
    expect(JSON.parse(String(options?.body))).toEqual({ provider: 'KIMI', model: 'test-model', apiKey: 'test-only-key', enabled: true, externalDataConsent: true })
  })
  it('connection testing uses only saved settings and explicit consent', async () => {
    await personalModelApi.test(true)
    const [url, options, , , timeout] = vi.mocked(request).mock.calls[0]
    expect(url).toBe('/assistant/model-settings/test')
    expect(JSON.parse(String(options?.body))).toEqual({ externalDataConsent: true })
    expect(timeout).toBe(40000)
  })
  it('reports local mode despite an enabled setting when platform egress is blocked', () => {
    expect(personalModelLabel(settings)).toBe('个人模型 · Kimi')
    expect(personalModelLabel({ ...settings, egressAllowed: false })).toContain('本地模式')
    expect(personalModelLabel({ ...settings, saved: null })).toContain('平台默认')
    expect(personalModelLabel({ ...settings, storageAvailable: false })).toContain('不可用')
  })
  it('does not surface raw errors that might contain credentials', () => {
    expect(modelSettingsError(new Error('sk-secret-provider-body'))).not.toContain('sk-secret')
    expect(modelSettingsError({ status: 401 })).toContain('重新登录')
    expect(modelSettingsError({ code: 'MODEL_SETTINGS_UNAVAILABLE' })).toContain('加密服务')
  })
})

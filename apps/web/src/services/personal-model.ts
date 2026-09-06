import { request } from './http'

export interface ModelProviderOption {
  id: string
  label: string
  endpoint: string
  modelHint: string
  documentationUrl: string
}
export interface SavedModel {
  provider: string
  model: string
  hasKey: boolean
  enabled: boolean
  revision: string
}
export interface ModelSettings {
  providers: ModelProviderOption[]
  storageAvailable: boolean
  egressAllowed: boolean
  saved: SavedModel | null
}
export interface SaveModelRequest {
  provider: string
  model: string
  apiKey: string
  enabled: boolean
  externalDataConsent: boolean
}
export interface ModelTestResult { success: boolean; message: string; latencyMs: number }

const path = '/assistant/model-settings'
export const personalModelApi = {
  get: (signal?: AbortSignal) => request<ModelSettings>(path, { signal, cache: 'no-store' }),
  save: (value: SaveModelRequest, signal?: AbortSignal) => request<ModelSettings>(path, {
    method: 'PUT', body: JSON.stringify(value), signal, cache: 'no-store',
  }),
  remove: (signal?: AbortSignal) => request<boolean>(path, { method: 'DELETE', signal }),
  test: (externalDataConsent: boolean, signal?: AbortSignal) => request<ModelTestResult>(`${path}/test`, {
    method: 'POST', body: JSON.stringify({ externalDataConsent }), signal, cache: 'no-store',
  }, undefined, 'json', 40000),
}

export function modelSettingsError(reason: unknown): string {
  const value = reason && typeof reason === 'object' ? reason as { status?: number; code?: string } : {}
  if (value.status === 401) return '登录已过期，请重新登录。'
  if (value.status === 403) return '当前账号没有使用智能助手的权限。'
  if (value.code === 'MODEL_SETTINGS_UNAVAILABLE') return '模型配置暂不可用，请联系管理员检查加密服务及平台数据外发设置。'
  if (value.code === 'REQUEST_TIMEOUT') return '请求超时，请稍后重试；重新打开可确认保存状态。'
  if (value.code === 'INVALID_MODEL_SETTINGS') return '请检查模型 ID、API Key 和授权勾选；连续测试请间隔 30 秒。'
  return '暂时无法完成请求，请稍后重试。'
}

export function personalModelLabel(settings: ModelSettings): string {
  if (!settings.egressAllowed) return '本地模式 · 平台未开放模型外发'
  if (!settings.saved?.enabled) return '使用平台默认模式'
  if (!settings.storageAvailable) return '个人模型暂不可用'
  const provider = settings.providers.find(item => item.id === settings.saved?.provider)
  return `个人模型 · ${provider?.label || settings.saved.provider}`
}

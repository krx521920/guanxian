import { describe, expect, it } from 'vitest'
import {
  applyUiPreferences,
  defaultUiPreferences,
  readUiPreferences,
  saveUiPreferences,
  type PreferenceStorage,
} from './ui-preferences'

function memoryStorage(seed: Record<string, string> = {}): PreferenceStorage & { values: Map<string, string> } {
  const values = new Map(Object.entries(seed))
  return {
    values,
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: (key) => { values.delete(key) },
  }
}

describe('界面偏好设置', () => {
  it('保存并重新读取经过校验的设置，同时清理旧键', () => {
    const storage = memoryStorage({
      'guanxian-primary-theme': 'rose',
      'guanxian-appearance': 'light',
      'guanxian-neutral-theme': 'slate',
    })

    expect(saveUiPreferences(storage, { primaryTheme: 'blue', appearance: 'dark' })).toBe(true)
    expect(readUiPreferences(storage)).toEqual({ primaryTheme: 'blue', appearance: 'dark' })
    expect(storage.values.has('guanxian-primary-theme')).toBe(false)
    expect(storage.values.has('guanxian-appearance')).toBe(false)
    expect(storage.values.has('guanxian-neutral-theme')).toBe(false)
  })

  it('损坏或越界的持久化值不会污染界面', () => {
    const storage = memoryStorage({
      'guanxian-ui-preferences': '{not-json',
      'guanxian-primary-theme': 'unknown',
      'guanxian-appearance': 'system',
    })

    expect(readUiPreferences(storage)).toEqual(defaultUiPreferences)
  })

  it('浏览器拒绝存储时明确报告失败并安全回退', () => {
    const storage: PreferenceStorage = {
      getItem: () => { throw new Error('blocked') },
      setItem: () => { throw new Error('blocked') },
      removeItem: () => { throw new Error('blocked') },
    }

    expect(readUiPreferences(storage)).toEqual(defaultUiPreferences)
    expect(saveUiPreferences(storage, { primaryTheme: 'violet', appearance: 'dark' })).toBe(false)
  })

  it('只向文档根节点应用已确认的主题与明暗模式', () => {
    const target = { dataset: { neutral: 'slate' } }
    applyUiPreferences(target, { primaryTheme: 'orange', appearance: 'dark' })
    expect(target.dataset).toEqual({ primary: 'orange', appearance: 'dark' })
  })
})

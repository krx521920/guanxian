import { describe, expect, it } from 'vitest'
import pageShell from '../../index.html?raw'
import shell from './AppShell.vue?raw'

describe('应用外壳设置入口', () => {
  it('设置按钮打开可保存的真实界面偏好对话框', () => {
    expect(shell).toContain('@click="openSettings"')
    expect(shell).toContain('@submit.prevent="saveSettings"')
    expect(shell).toContain('saveUiPreferences(localStorage')
    expect(shell).toContain('当前浏览器未允许保存界面偏好')
  })

  it('不再把尚未完成的能力包装成 AI 平台', () => {
    expect(shell).toContain('<small>管理协作平台</small>')
    expect(shell).not.toContain('<small>AI 管理协作平台</small>')
    expect(pageShell).toContain('<title>管线智联 · 管理协作平台</title>')
    expect(pageShell).not.toContain('AI 管理协作平台')
  })
})

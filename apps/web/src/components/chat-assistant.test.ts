import { describe, expect, it } from 'vitest'
import { assistantErrorMessage, assistantModeLabel, safeCitationUrl } from './chat-assistant'

describe('chat assistant safety helpers', () => {
  it('only exposes HTTP(S) citation links', () => {
    expect(safeCitationUrl('https://example.org/policy?id=1')).toBe('https://example.org/policy?id=1')
    expect(safeCitationUrl('http://example.org/source')).toBe('http://example.org/source')
    expect(safeCitationUrl('javascript:alert(1)')).toBeNull()
    expect(safeCitationUrl('knowledge-document:123')).toBeNull()
    expect(safeCitationUrl('not a url')).toBeNull()
  })

  it('turns security and authorization failures into actionable copy', () => {
    expect(assistantErrorMessage({ status: 401 })).toContain('重新登录')
    expect(assistantErrorMessage({ status: 403 })).toContain('管理协会')
    expect(assistantErrorMessage({ code: 'UNSAFE_KNOWLEDGE_INPUT' })).toContain('换一种')
    expect(assistantErrorMessage({ code: 'RAG_LIMIT_EXCEEDED' })).toContain('缩短')
    expect(assistantErrorMessage({ code: 'REQUEST_TIMEOUT' })).toContain('超时')
    expect(assistantErrorMessage(new Error('secret backend detail'))).not.toContain('secret')
  })

  it('labels the source mode without implying that local queries use a model', () => {
    expect(assistantModeLabel('LOCAL_BUSINESS_QUERY')).toBe('业务查询模式')
    expect(assistantModeLabel('RETRIEVAL_SUMMARY')).toBe('知识库模式')
    expect(assistantModeLabel('SPRING_AI_AGENT')).toBe('AI 综合模式')
    expect(assistantModeLabel('AUTO')).toBe('自动选择模式')
  })
})

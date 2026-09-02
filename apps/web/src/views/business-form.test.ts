import { describe, expect, it } from 'vitest'
import { apiActionMessage, displayBusinessStatus, nullableText, splitItems } from './business-form'

describe('business form helpers', () => {
  it('normalizes multiline business tags and removes duplicates', () => {
    expect(splitItems('燃气, 探测\n燃气；应急')).toEqual(['燃气', '探测', '应急'])
  })

  it('normalizes optional text without inventing values', () => {
    expect(nullableText('  ')).toBeNull()
    expect(nullableText(' 北京 ')).toBe('北京')
  })

  it('maps persisted workflow states for display', () => {
    expect(displayBusinessStatus('PENDING_REVIEW')).toBe('待审核')
    expect(displayBusinessStatus('ACCEPTED')).toBe('已接受')
    expect(displayBusinessStatus('REJECTED')).toBe('已拒绝')
    expect(displayBusinessStatus('ASSOCIATION_RECOMMENDATION')).toBe('协会推荐')
    expect(displayBusinessStatus('PILOT')).toBe('试点项目')
    expect(displayBusinessStatus('PARTNERS')).toBe('合作协会')
    expect(displayBusinessStatus('CUSTOM')).toBe('CUSTOM')
  })

  it('warns that a timed-out write may still complete instead of claiming failure', () => {
    expect(apiActionMessage({ code: 'REQUEST_TIMEOUT' }, '保存失败')).toContain('刷新数据确认结果')
  })
})

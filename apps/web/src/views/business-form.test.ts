import { describe, expect, it } from 'vitest'
import { displayBusinessStatus, nullableText, splitItems } from './business-form'

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
    expect(displayBusinessStatus('CUSTOM')).toBe('CUSTOM')
  })
})

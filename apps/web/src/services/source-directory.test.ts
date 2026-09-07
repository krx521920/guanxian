import { describe, expect, it } from 'vitest'
import { sourceLink, tenderDeadlineLabel } from './source-directory'

describe('source directory presentation', () => {
  it('does not render active unsafe or invented links', () => {
    for (const value of ['', 'www.example.com', 'javascript:alert(1)', '//example.com', 'https://a:b@example.com', 'https://example.com/a b']) expect(sourceLink(value)).toBeNull()
    expect(sourceLink('https://example.com/a')).toBe('https://example.com/a')
  })
  it('interprets the source deadline in Beijing time without promising participation eligibility', () => {
    expect(tenderDeadlineLabel('2026-09-07 20:00:00', Date.parse('2026-09-07T12:00:00Z'))).toBe('已过所载截止时间')
    expect(tenderDeadlineLabel('2026-09-07 20:00:00', Date.parse('2026-09-07T11:59:59Z'))).toBe('尚未到所载截止时间')
    expect(tenderDeadlineLabel(undefined)).toBe('截止时间待补充')
    expect(tenderDeadlineLabel('bad')).toBe('截止时间待核实')
  })
})

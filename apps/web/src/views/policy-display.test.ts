import { describe, expect, it } from 'vitest'
import { displayEffectiveDate } from './policy-display'

describe('displayEffectiveDate', () => {
  it('keeps a published effective date', () => {
    expect(displayEffectiveDate('2026-09-01')).toBe('2026-09-01')
  })

  it.each([null, '', '   '])('shows a safe placeholder for %j', (effectiveDate) => {
    expect(displayEffectiveDate(effectiveDate)).toBe('暂未公布')
  })
})

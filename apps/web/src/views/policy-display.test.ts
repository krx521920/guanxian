import { describe, expect, it } from 'vitest'
import { displayEffectiveDate, safeExternalUrl } from './policy-display'

describe('displayEffectiveDate', () => {
  it('keeps a published effective date', () => {
    expect(displayEffectiveDate('2026-09-01')).toBe('2026-09-01')
  })

  it.each([null, '', '   '])('shows a safe placeholder for %j', (effectiveDate) => {
    expect(displayEffectiveDate(effectiveDate)).toBe('暂未公布')
  })
})

describe('safeExternalUrl', () => {
  it('keeps absolute HTTP and HTTPS sources', () => {
    expect(safeExternalUrl('https://example.test/policy?id=1')).toBe('https://example.test/policy?id=1')
    expect(safeExternalUrl('http://example.test/policy')).toBe('http://example.test/policy')
  })

  it('hides unsupported, credentialed and malformed sources', () => {
    for (const value of ['javascript:alert(1)', 'https://user:secret@example.test/policy', '/relative', 'not a url']) {
      expect(safeExternalUrl(value)).toBeNull()
    }
  })
})

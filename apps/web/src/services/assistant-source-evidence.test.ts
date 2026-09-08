import { describe, expect, it } from 'vitest'
import { parseBusinessResults, businessResultText } from './assistant-business-results'
import { sourceEvidenceFixture } from '../../tests/ui/source-evidence-fixtures'

describe('source receipts are validated server data, not model Markdown', () => {
  it.each(['TENDER', 'ACTIVITY'] as const)('preserves provenance and classification for %s', kind => {
    const receipt = sourceEvidenceFixture(kind)
    expect(parseBusinessResults([receipt])[0].items[0].source?.kind).toBe(kind)
    expect(businessResultText(receipt, true)).toContain('EV-FIXTURE')
    expect(businessResultText(receipt, true)).toContain('https://example.test/notice')
    expect(businessResultText(receipt, true)).toContain('非实时复核')
    expect(businessResultText(receipt, true)).toContain('企业份额未披露')
  })
  it.each(['javascript:alert(1)', 'data:text/html,bad', 'https://u:p@example.test', 'https://example.test/with space', '//example.test', 'https://example.test/a;https://b.test'])('rejects unsafe source URL %s', sourceUrl => {
    const receipt = sourceEvidenceFixture(); receipt.items[0].source!.sourceUrl = sourceUrl
    expect(() => parseBusinessResults([receipt])).toThrow('INVALID_BUSINESS_RESULTS')
  })
  it('rejects private fields, mismatched kinds and sources disguised as member cards', () => {
    const changed = sourceEvidenceFixture(); changed.items[0].fields = { contactPhone: 'secret' }
    expect(() => parseBusinessResults([changed])).toThrow()
    const mismatch = sourceEvidenceFixture(); mismatch.items[0].source!.kind = 'ACTIVITY'
    expect(() => parseBusinessResults([mismatch])).toThrow()
    const member = sourceEvidenceFixture(); member.kind = 'MEMBERS'
    expect(() => parseBusinessResults([member])).toThrow()
    const invalid = sourceEvidenceFixture(); invalid.items[0].source = null
    expect(() => parseBusinessResults([invalid])).toThrow()
  })
})

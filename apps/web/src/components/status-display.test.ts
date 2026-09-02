import { describe, expect, it } from 'vitest'
import { displayStatus } from './status-display'

describe('displayStatus', () => {
  it('normalizes backend enum values for the interface', () => {
    expect(displayStatus('COMPLETED')).toBe('已完成')
    expect(displayStatus('PENDING_REVIEW')).toBe('待审核')
    expect(displayStatus('HIGH')).toBe('高')
  })

  it('keeps existing Chinese values unchanged', () => {
    expect(displayStatus('待完善')).toBe('待完善')
  })

  it('renders absent API status values without an empty badge', () => {
    expect(displayStatus(null)).toBe('未知')
    expect(displayStatus(undefined)).toBe('未知')
    expect(displayStatus('')).toBe('未知')
  })
})

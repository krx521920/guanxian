import { describe, expect, it } from 'vitest'
import { sourceLink, sourceLinks, tenderDeadlineLabel, tenderStageLabel } from './source-directory'

describe('source directory presentation', () => {
  it('keeps evidence-backed candidates and awards historical even with conflicting old deadlines', () => {
    expect(tenderStageLabel({ 记录状态代码: 'CANDIDATE_NOTICE', 记录状态: '候选公示', 截止或开标时间: '2030-01-01 00:00:00' })).toBe('候选公示 · 非在招机会')
    expect(tenderStageLabel({ 记录状态代码: 'AWARD_DISCLOSED', 记录状态: '发行人披露中标' })).toBe('发行人披露中标 · 非在招机会')
  })
  it('does not render active unsafe or invented links', () => {
    for (const value of ['', 'www.example.com', 'javascript:alert(1)', '//example.com', 'https://a:b@example.com', 'https://example.com/a b']) expect(sourceLink(value)).toBeNull()
    expect(sourceLink('https://example.com/a')).toBe('https://example.com/a')
  })
  it('interprets the source deadline in Beijing time without promising participation eligibility', () => {
    expect(tenderDeadlineLabel('2026-09-07 20:00:00', Date.parse('2026-09-07T12:00:00Z'))).toBe('已过所载截止时间')
    expect(tenderDeadlineLabel('2026-09-07 20:00:00', Date.parse('2026-09-07T11:59:59Z'))).toBe('尚未到所载截止时间')
    expect(tenderDeadlineLabel(undefined)).toBe('截止时间待补充')
    expect(tenderDeadlineLabel('bad')).toBe('截止时间待核实')
    expect(tenderDeadlineLabel('2026-02-30 09:00:00')).toBe('截止时间待核实')
  })
  it('never treats a plan, award or closed acquisition window as an available bid', () => {
    const now = Date.parse('2026-09-08T10:00:00Z')
    expect(tenderStageLabel({ 公告类型: '招标计划' }, now)).toBe('招标计划 · 等待正式公告')
    expect(tenderStageLabel({ 公告类型: '中标结果', 截止或开标时间: '2027-01-01 00:00:00' }, now)).toBe('中标结果 · 非在招机会')
    expect(tenderStageLabel({ 文件获取开始时间: '2026-09-09 09:00:00' }, now)).toBe('尚未到所载文件获取期')
    expect(tenderStageLabel({ 文件获取截止时间: '2026-09-08 17:00:00', 截止或开标时间: '2026-09-22 09:00:00' }, now)).toBe('已过文件获取期 · 请核对是否已获取文件')
    expect(tenderStageLabel({ 文件获取截止时间: '2026-09-08 17:00:00', 截止或开标时间: '2026-09-08 18:00:00' }, now)).toBe('已过所载截止时间')
    expect(tenderStageLabel({}, now)).toBe('截止时间待补充')
  })
  it('splits explicitly labelled original and correction notices without inventing links', () => {
    const original = 'https://example.test/original', correction = 'https://example.test/correction'
    const combined = `${original}；更正：${correction}`
    expect(sourceLink(combined)).toBeNull()
    expect(sourceLinks({ 原文链接: combined })).toEqual([
      { label: '查看来源原文 ↗', url: original }, { label: '查看更正公告 ↗', url: correction },
    ])
    expect(sourceLinks({ 原文链接: original, 更正公告: correction })).toHaveLength(2)
    expect(sourceLinks({ 原文链接: original, 更正公告: original })).toHaveLength(1)
    for (const value of ['javascript:alert(1)', `${original}；更正：javascript:alert(1)`, `${original}https://evil.test`, 'https://example.test\\@evil.test']) {
      expect(sourceLink(value)).toBeNull()
      expect(sourceLinks({ 原文链接: value })).toEqual([])
    }
  })
})

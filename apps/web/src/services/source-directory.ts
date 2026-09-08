import { request } from './http'

export type SourceKind = 'ENTERPRISE' | 'POLICY' | 'ASSOCIATION' | 'TENDER' | 'ACTIVITY'
export interface SourceEntry {
  id: string
  sourceId: string
  title: string
  enterpriseId?: string | null
  fields: Record<string, string>
  importedAt: string
  originalFields?: Record<string, string> | null
  correction?: { id: string; checkedOn?: string; reason?: string; evidenceUrls?: string[] } | null
  evidence?: { recordId: string; title: string; checkedOn: string; sourceUrl: string; supportingUrls: string[] } | null
}
export interface SourcePage { items: SourceEntry[]; total: number; page: number; size: number }
export const sourceDirectory = (kind: SourceKind, q = '', page = 0, size = 20, recordId?: string) =>
  request<SourcePage>(`/source-directory?kind=${kind}&q=${encodeURIComponent(q)}&page=${page}&size=${size}${recordId ? `&recordId=${encodeURIComponent(recordId)}` : ''}`)

export function sourceLink(value?: string): string | null {
  if (!value || !/^https?:\/\//i.test(value) || /[\s\\\u0000-\u001f\u007f]/.test(value)
      || (value.match(/https?:\/\//gi)?.length ?? 0) !== 1 || /[；;]/.test(value)) return null
  try {
    const url = new URL(value)
    return url.username || url.password ? null : url.href
  } catch { return null }
}

export function sourceLinks(fields: Record<string, string>): { label: string; url: string }[] {
  const result: { label: string; url: string }[] = []
  const add = (label: string, value?: string) => {
    const url = sourceLink(value)
    if (url && !result.some(item => item.url === url)) result.push({ label, url })
  }
  const raw = fields['原文链接'] || fields['官方原文链接'] || fields['官网'] || fields['网址']
  // Support already-imported compound values without turning arbitrary text into a URL.
  const combined = raw?.match(/^(https?:\/\/[^\s；;]+)[；;]更正[：:](https?:\/\/[^\s；;]+)$/i)
  if (combined) {
    add('查看来源原文 ↗', combined[1]); add('查看更正公告 ↗', combined[2])
  } else add('查看来源原文 ↗', raw)
  add('查看更正公告 ↗', fields['更正公告'])
  return result
}

export function tenderDeadlineLabel(value: string | undefined, now = Date.now()): string {
  if (!value) return '截止时间待补充'
  // This dataset's unzoned source timestamps are Beijing time, not the viewer's timezone.
  const date = beijingTimestamp(value)
  if (!Number.isFinite(date)) return '截止时间待核实'
  return date <= now ? '已过所载截止时间' : '尚未到所载截止时间'
}

function beijingTimestamp(value?: string): number {
  const parts = value?.trim().match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2}))?$/)
  if (!parts) return NaN
  const [, year, month, day, hour, minute, second = '0'] = parts
  const utc = Date.UTC(+year, +month - 1, +day, +hour, +minute, +second)
  const check = new Date(utc)
  if (check.getUTCFullYear() !== +year || check.getUTCMonth() !== +month - 1 || check.getUTCDate() !== +day
      || check.getUTCHours() !== +hour || check.getUTCMinutes() !== +minute || check.getUTCSeconds() !== +second) return NaN
  return utc - 8 * 60 * 60 * 1000
}

export function tenderStageLabel(fields: Record<string, string>, now = Date.now()): string {
  // Reviewed history is not an open bidding opportunity, even if an old source has a future-looking deadline.
  if (fields['记录状态代码']) return `${fields['记录状态'] || '历史公告记录'} · 非在招机会`
  const type = fields['公告类型']
  if (type === '招标计划') return '招标计划 · 等待正式公告'
  if (['中标结果', '成交公告', '终止公告', '中止公告'].includes(type || '')) return `${type} · 非在招机会`
  if (type === '更正公告') return '更正公告 · 请核对原公告'
  const end = beijingTimestamp(fields['截止或开标时间'])
  if (Number.isFinite(end) && end <= now) return '已过所载截止时间'
  const start = beijingTimestamp(fields['文件获取开始时间']), acquisitionEnd = beijingTimestamp(fields['文件获取截止时间'])
  if (Number.isFinite(start) && start > now) return '尚未到所载文件获取期'
  if (Number.isFinite(acquisitionEnd) && acquisitionEnd <= now) return '已过文件获取期 · 请核对是否已获取文件'
  return tenderDeadlineLabel(fields['截止或开标时间'], now)
}

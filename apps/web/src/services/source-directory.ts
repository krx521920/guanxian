import { request } from './http'

export type SourceKind = 'ENTERPRISE' | 'POLICY' | 'ASSOCIATION' | 'TENDER'
export interface SourceEntry {
  id: string
  sourceId: string
  title: string
  enterpriseId?: string | null
  fields: Record<string, string>
  importedAt: string
}
export interface SourcePage { items: SourceEntry[]; total: number; page: number; size: number }
export const sourceDirectory = (kind: SourceKind, q = '', page = 0, size = 20) =>
  request<SourcePage>(`/source-directory?kind=${kind}&q=${encodeURIComponent(q)}&page=${page}&size=${size}`)

export function sourceLink(value?: string): string | null {
  if (!value || !/^https?:\/\//i.test(value) || /\s/.test(value)) return null
  try {
    const url = new URL(value)
    return url.username || url.password ? null : url.href
  } catch { return null }
}

export function tenderDeadlineLabel(value: string | undefined, now = Date.now()): string {
  if (!value) return '截止时间待补充'
  // This dataset's unzoned source timestamps are Beijing time, not the viewer's timezone.
  const date = Date.parse(value.trim().replace(' ', 'T') + '+08:00')
  if (!Number.isFinite(date)) return '截止时间待核实'
  return date <= now ? '已过所载截止时间' : '尚未到所载截止时间'
}

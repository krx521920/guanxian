import type { MemberProfile } from '../types/domain'

export interface BusinessEvidence { criterion: string; state: 'MATCHED' | 'UNMET' | 'INSUFFICIENT'; field: string; observed: string; explanation: string }
export interface BusinessItem { id: string; name: string; target: 'MEMBER' | 'NONE'; fields: Record<string, string>; evidence: BusinessEvidence[] }
export interface BusinessResult {
  schemaVersion: 1; id: string; kind: string; status: 'OK' | 'FORBIDDEN' | 'FAILED' | 'INVALID' | 'UNAVAILABLE'; label: string;
  associationId: string | null; scope: string; filters: Record<string, string>; queriedAt: string; total: number; items: BusinessItem[]
}
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v)
const text = (v: unknown, max = 400): v is string => typeof v === 'string' && v.length <= max
const date = (v: unknown) => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T/.test(v) && Number.isFinite(Date.parse(v))
const stringMap = (v: unknown): v is Record<string, string> => object(v) && Object.keys(v).length <= 10
  && Object.entries(v).every(([k, val]) => text(k, 50) && text(val) && !['__proto__', 'constructor', 'prototype'].includes(k))
const kinds = ['MEMBERS', 'SELECTED_MEMBERS', 'COMPARISON', 'RECOMMENDATIONS', 'MEMBER_FIT_CHECK', 'OFFERINGS', 'DEMANDS', 'MATCHES', 'COLLABORATIONS']
const fields = ['category', 'capabilities', 'products', 'services', 'status', 'updatedAt']
function validItem(v: unknown): v is BusinessItem {
  return object(v) && uuid(v.id) && text(v.name) && ['MEMBER', 'NONE'].includes(String(v.target)) && stringMap(v.fields)
    && (v.target !== 'MEMBER' || Object.keys(v.fields).every(key => fields.includes(key)))
    && Array.isArray(v.evidence) && v.evidence.length <= 8 && v.evidence.every(e => object(e)
      && text(e.criterion, 80) && ['MATCHED', 'UNMET', 'INSUFFICIENT'].includes(String(e.state))
      && fields.includes(String(e.field)) && text(e.observed) && text(e.explanation))
}
/** Only server data parts are passed here. Model text/Markdown is never parsed as business UI. */
export function parseBusinessResults(value: unknown): BusinessResult[] {
  if (value == null) return [] // Older servers remain usable as text-only chat.
  if (!Array.isArray(value) || value.length > 8 || !value.every(v => object(v) && v.schemaVersion === 1 && uuid(v.id)
    && kinds.includes(String(v.kind)) && ['OK', 'FORBIDDEN', 'FAILED', 'INVALID', 'UNAVAILABLE'].includes(String(v.status))
    && (v.associationId === null || uuid(v.associationId)) && text(v.label, 100) && text(v.scope) && stringMap(v.filters) && date(v.queriedAt)
    && Number.isSafeInteger(v.total) && (v.total as number) >= 0 && Array.isArray(v.items) && v.items.length <= 10
    && v.items.every(validItem) && new Set(v.items.map(i => i.id)).size === v.items.length && (v.total as number) >= v.items.length
    && (v.status === 'OK' || (v.items.length === 0 && v.total === 0))
    && (v.status !== 'OK' || !['SELECTED_MEMBERS', 'COMPARISON', 'RECOMMENDATIONS', 'MEMBER_FIT_CHECK'].includes(String(v.kind))
      || (v.total === v.items.length && v.items.length <= 4 && v.items.length >= (v.kind === 'COMPARISON' ? 2 : 1)))
    && (!['MEMBERS', 'SELECTED_MEMBERS', 'COMPARISON', 'RECOMMENDATIONS', 'MEMBER_FIT_CHECK'].includes(String(v.kind)) || v.items.every(i => i.target === 'MEMBER')))) {
    throw new Error('INVALID_BUSINESS_RESULTS')
  }
  if (new Set(value.map(v => v.id)).size !== value.length) throw new Error('INVALID_BUSINESS_RESULTS')
  return value as BusinessResult[]
}
export const memberFields: Record<string, string> = { category: '企业分类', capabilities: '核心能力', products: '产品', services: '服务', status: '审核状态', updatedAt: '档案更新时间' }
export const memberStatus = (value: string) => ({ ACTIVE: '已认证', PENDING_REVIEW: '待审核', INCOMPLETE: '待完善', DISABLED: '已停用', DELETED: '已删除' }[value] || value || '未提供')
export function fieldValue(item: BusinessItem, key: string): string {
  const value = item.fields[key]
  if (!value) return '未提供'
  if (key === 'status') return memberStatus(value)
  if (key === 'updatedAt') return date(value) ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '未提供'
  return value
}
/** Deliberately excludes contacts, credit code and email from the comparison surface. */
export function memberItem(member: MemberProfile): BusinessItem {
  return { id: member.id, name: member.name, target: 'MEMBER', evidence: [], fields: {
    category: member.category, capabilities: member.capabilities.join('、'), products: member.products.join('、'),
    services: (member.services || []).join('、'), status: member.status, updatedAt: member.updatedAt,
  } }
}

export interface SelectedEnterprise { id: string; name: string }
export function selectedEnterpriseIds(value: unknown): string[] {
  if (value == null) return []
  if (!Array.isArray(value) || value.length > 4 || !value.every(uuid) || new Set(value).size !== value.length) throw new Error('INVALID_ENTERPRISE_SELECTION')
  return [...value]
}
export function followupSelection(items: SelectedEnterprise[]): SelectedEnterprise[] {
  selectedEnterpriseIds(items.map(item => item.id))
  if (items.some(item => !text(item.name) || !item.name.trim())) throw new Error('INVALID_ENTERPRISE_SELECTION')
  return items.map(item => ({ id: item.id, name: item.name }))
}

/** Human-readable snapshot, not a live report or a CSV/HTML export. Only allowlisted fields. */
export function businessResultText(result: BusinessResult, incomplete: boolean): string {
  parseBusinessResults([result])
  const lines = [incomplete ? '[未完成回答中的已返回查询]' : '[业务查询快照]', result.label,
    `查询编号：${result.id}`, `查询时间：${result.queriedAt}`, `查询范围：${result.scope}`,
    `查询组织上下文：${result.associationId || '全平台上下文（仍受当前账号权限限制）'}`,
    ...Object.entries(result.filters).map(([key, value]) => `${key}：${value}`),
    `查询状态：${result.status}`, ...(result.status === 'OK' ? [`共 ${result.total} 条，展示 ${result.items.length} 条；不代表完整数据导出。`] : ['查询未成功，不能据此认定没有数据。'])]
  if (result.kind === 'RECOMMENDATIONS') lines.push('条件来自本轮工具参数，请核对是否正确理解您的要求；这不是资质或履约认证。')
  if (result.kind === 'MEMBER_FIT_CHECK') lines.push('条件由用户手动确认；未调用模型，仅核对登记字段，不是资质或履约认证。')
  for (const item of result.items) {
    lines.push('', `${item.name}（ID：${item.id}）`)
    const labels = item.target === 'MEMBER' ? memberFields : Object.fromEntries(Object.keys(item.fields).map(key => [key, key]))
    for (const [key, label] of Object.entries(labels)) lines.push(`${label}：${fieldValue(item, key)}`)
    for (const e of item.evidence) lines.push(`条件“${e.criterion}”：${{ MATCHED: '符合登记条件', UNMET: '登记值不符合', INSUFFICIENT: '资料不足' }[e.state]}；依据 ${memberFields[e.field]}=${e.observed || '未提供'}；${e.explanation}`)
  }
  lines.push('', '以上为查询时快照，不代表当前最新状态；资料不足不等于没有能力，登记内容仍需人工核验。')
  return lines.join('\n')
}

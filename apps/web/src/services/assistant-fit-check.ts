import { request } from './http'
import { parseBusinessResults, selectedEnterpriseIds, type BusinessResult } from './assistant-business-results'

export const fitFields = { capabilities: '核心能力', products: '产品', services: '服务', category: '企业分类', status: '审核状态' }
export const fitStatuses = { ACTIVE: '已认证', PENDING_REVIEW: '待审核', INCOMPLETE: '待完善', DISABLED: '已停用', DELETED: '已删除' }
export interface FitCriterion { field: keyof typeof fitFields; value: string }
export interface FitCheckResponse { criteria: FitCriterion[]; result: BusinessResult }
export function normalizeFitCriteria(value: unknown): FitCriterion[] {
  if (!Array.isArray(value) || !value.length || value.length > 8) throw new Error('请填写 1–8 项条件')
  const criteria = value.map(c => {
    if (!c || typeof c !== 'object' || !Object.hasOwn(fitFields, c.field) || typeof c.value !== 'string'
      || !c.value.trim() || c.value.length > 80) throw new Error('条件字段无效或内容为空、超过 80 字')
    const item = { field: c.field as FitCriterion['field'], value: c.value.trim() }
    if (item.field === 'status' && !Object.hasOwn(fitStatuses, item.value)) throw new Error('请选择有效审核状态')
    return item
  })
  if (new Set(criteria.map(c => `${c.field}\n${c.value.toLowerCase()}`)).size !== criteria.length) throw new Error('请移除重复条件')
  return criteria
}
export function criteriaFromResult(result: BusinessResult): FitCriterion[] {
  try {
    const rows = result.items.map(item => item.evidence.map(e => ({ field: e.field, value: e.criterion })))
    if (!rows.length || rows.some(row => JSON.stringify(row) !== JSON.stringify(rows[0]))) return []
    return normalizeFitCriteria(rows[0])
  } catch { return [] }
}
export function verifyFitResponse(value: unknown, ids: string[], criteria: FitCriterion[]): FitCheckResponse {
  if (!value || typeof value !== 'object' || !('criteria' in value) || !('result' in value)) throw new Error('核对结果格式无效')
  const echoed = normalizeFitCriteria(value.criteria)
  const [result] = parseBusinessResults([value.result])
  const changedIds = JSON.stringify(result.items.map(i => i.id)) !== JSON.stringify(ids)
  const changedEvidence = result.items.some(item => {
    const itemCriteria = item.evidence.map(e => ({ field: e.field, value: e.criterion }))
    return JSON.stringify(itemCriteria) !== JSON.stringify(criteria)
      || item.evidence.some(e => e.observed !== (item.fields[e.field] || ''))
  })
  if (result.kind !== 'MEMBER_FIT_CHECK' || JSON.stringify(echoed) !== JSON.stringify(criteria)
    || (result.status === 'OK' && (changedIds || changedEvidence))) {
    throw new Error('返回的企业或条件与本次提交不一致，未展示结果')
  }
  return { criteria: echoed, result }
}
export async function checkMemberFit(ids: string[], input: FitCriterion[], associationId?: string, signal?: AbortSignal) {
  const enterpriseIds = selectedEnterpriseIds(ids), criteria = normalizeFitCriteria(input)
  if (!enterpriseIds.length) throw new Error('请至少选择一家企业')
  const value = await request<unknown>('/assistant/members/fit-check', { method: 'POST', signal,
    body: JSON.stringify({ associationId: associationId || null, enterpriseIds, criteria }) })
  return verifyFitResponse(value, enterpriseIds, criteria)
}

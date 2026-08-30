import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

const view = readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'FederationView.vue'), 'utf8')

describe('友好协会界面安全契约', () => {
  it('关系审批默认不开放会员数据，并由操作者显式勾选', () => {
    expect(view).toContain("allowMemberData: false")
    expect(view).toContain('v-model="accessForm.allowMemberData"')
  })

  it('失效关系不会留下必然失败的恢复或推荐审批按钮', () => {
    expect(view).toContain("relationshipState(item) === 'SUSPENDED'")
    expect(view).toContain('item.suspendedByAssociationId === currentAssociationId')
    expect(view).toContain('hasActiveRelationship(item.sourceAssociationId, item.targetAssociationId)')
  })

  it('申请、策略和推荐操作严格按当前协会所在方向开放', () => {
    expect(view).toContain('item.targetAssociationId === currentAssociationId.value')
    expect(view).toContain('item.applicantAssociationId === currentAssociationId.value')
    expect(view).toContain('item.sourceAssociationId === currentAssociationId.value')
  })
})

import { readFileSync, readdirSync } from 'node:fs'
import { dirname, extname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

const sourceRoot = dirname(dirname(fileURLToPath(import.meta.url)))

function sourceFiles(directory: string, extension: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) return sourceFiles(path, extension)
    return extname(entry.name) === extension ? [path] : []
  })
}

function lineAt(source: string, offset: number): number {
  return source.slice(0, offset).split('\n').length
}

function buttonLabel(markup: string): string {
  const label = markup
    .replace(/<[^>]+>/g, ' ')
    .replace(/\{\{[\s\S]*?\}\}/g, '…')
    .replace(/\s+/g, ' ')
    .trim()
  return label || '(无文字按钮)'
}

function isSubmitButton(source: string, offset: number, openingTag: string): boolean {
  if (/type\s*=\s*["']submit["']/.test(openingTag)) return true
  if (/type\s*=/.test(openingTag)) return false

  const formStart = source.lastIndexOf('<form', offset)
  const formEnd = source.lastIndexOf('</form>', offset)
  if (formStart < 0 || formStart < formEnd) return false
  const formOpeningEnd = source.indexOf('>', formStart)
  if (formOpeningEnd < 0 || formOpeningEnd >= offset) return false
  const formOpeningTag = source.slice(formStart, formOpeningEnd + 1)
  return /(?:@submit|v-on:submit)(?:\.[\w-]+)*\s*=/.test(formOpeningTag)
}

describe('生产界面交互契约', () => {
  it('每个原生按钮都有点击行为或明确的表单提交语义', () => {
    const inertButtons: string[] = []
    const buttonPattern = /<button\b[^>]*\/>|<button\b[^>]*>[\s\S]*?<\/button>/gi

    for (const file of sourceFiles(sourceRoot, '.vue')) {
      const source = readFileSync(file, 'utf8')
      for (const match of source.matchAll(buttonPattern)) {
        const markup = match[0]
        const openingTag = markup.match(/^<button\b[^>]*>/i)?.[0] ?? markup
        const hasClick = /(?:@click|v-on:click)(?:\.[\w-]+)*\s*=/.test(openingTag)
        const submitsForm = isSubmitButton(source, match.index ?? 0, openingTag)
        if (!hasClick && !submitsForm) {
          inertButtons.push(
            `${relative(sourceRoot, file).replaceAll('\\', '/')}:${lineAt(source, match.index ?? 0)} ${buttonLabel(markup)}`,
          )
        }
      }
    }

    expect(inertButtons, `发现无实际行为的按钮：\n${inertButtons.join('\n')}`).toEqual([])
  })

  it('生产 API 客户端不引用模拟数据模块', () => {
    const apiClient = readFileSync(join(sourceRoot, 'services', 'platform-api.ts'), 'utf8')
    expect(apiClient).not.toMatch(/(?:from|import\s*\()\s*["'][^"']*mocks\/data["']/)
  })

  it('业务弹窗使用可见的固定遮罩，并为匹配依据提供对话框语义', () => {
    const stylesheet = readFileSync(join(sourceRoot, 'styles', 'main.css'), 'utf8')
    const matchingView = readFileSync(join(sourceRoot, 'views', 'MatchingView.vue'), 'utf8')

    expect(stylesheet).toMatch(/\.modal-backdrop\s*\{[\s\S]*?position:\s*fixed;[\s\S]*?z-index:\s*80;/)
    expect(stylesheet).toMatch(/\.modal-card\s*\{[\s\S]*?max-height:\s*calc\(100vh - 64px\);/)
    expect(matchingView).toContain('@click="rulesOpen = true"')
    expect(matchingView).toContain('role="dialog" aria-modal="true" aria-labelledby="matching-rules-title"')
    expect(matchingView).toContain('id="matching-rules-title"')
  })

  it('匹配详情把邀请、应答、洽谈、反馈和成果归档连接到显式用户操作', () => {
    const matchingView = readFileSync(join(sourceRoot, 'views', 'MatchingView.vue'), 'utf8')
    expect(matchingView).toContain('@submit.prevent="sendInvitation"')
    expect(matchingView).toContain('@submit.prevent="respondInvitation(invitation, true)"')
    expect(matchingView).toContain('@submit.prevent="addNegotiation"')
    expect(matchingView).toContain('@submit.prevent="submitFeedback"')
    expect(matchingView).toContain('@submit.prevent="archiveOutcome"')
    expect(matchingView).toContain('v-if="canViewInvitationResponse(invitation)"')
    expect(matchingView).toContain(':disabled="busy || !canRespondInvitation(invitation)"')
    expect(matchingView).toContain('canArchiveOutcome(selected)" class="workflow-form"')
    for (const action of ['RECOMMEND', 'CONFIRM', 'INVITE', 'NEGOTIATE', 'FEEDBACK', 'ARCHIVE', 'CLOSE']) {
      expect(matchingView).toContain(`hasMatchAction(item, '${action}')`)
    }
    expect(matchingView).toContain("'ENTERPRISE_ADMIN'].includes(auth.user.value?.role")
    expect(matchingView).not.toContain("'ENTERPRISE_MEMBER'].includes(auth.user.value?.role")
    expect(matchingView).toContain('invitationStatus(invitation) === \'EXPIRED\'')
    expect(matchingView).toContain('@click="loadWorkflow(selected)"')
    expect(matchingView).toContain('<PaginationBar')
    expect(matchingView).toContain('platformApi.matchGenerationDemands')
    expect(matchingView).not.toContain("platformApi.demands('', false")
    expect(matchingView).toContain('loadMatchWorkflowSections(platformApi, item.id)')
    expect(matchingView).toContain('workflowSectionErrors.outcomes')
    expect(matchingView).toContain('canOpenMatchCollaboration(item, auth.user.value, workflowSectionReadable)')
    expect(matchingView).toContain('!workflowLoading && canOpenCollaboration(selected)')
    expect(matchingView).toContain('isValidInvitationResponse(accepted, invitationResponseComment.value)')
    expect(matchingView).toContain('拒绝时必须填写原因')
    expect(matchingView).toContain('v-model="invitationResponseComment" maxlength="1000"')
    expect(matchingView).toContain(":maxlength=\"negotiationForm.stage === 'TERMINATED' ? 1000 : 5000\"")
  })

  it('企业可以对真实产品、服务、需求、会员资料和匹配记录逐项授权并撤销', () => {
    const catalogView = readFileSync(join(sourceRoot, 'views', 'EcosystemCatalogView.vue'), 'utf8')
    const memberEditView = readFileSync(join(sourceRoot, 'views', 'MemberEditView.vue'), 'utf8')
    const matchingView = readFileSync(join(sourceRoot, 'views', 'MatchingView.vue'), 'utf8')

    expect(catalogView).toContain('platformApi.associationConsentTargets()')
    expect(catalogView).toContain('platformApi.grantAssociationConsent({')
    expect(catalogView).toContain('platformApi.revokeAssociationConsent(item)')
    expect(catalogView).toContain('v-if="!item.deleted && isShareReady(item)"')
    expect(catalogView).toContain("canCatalog(item, 'RESTORE')")
    expect(catalogView).toContain("canCatalog(item, 'ENABLE')")
    expect(catalogView).toContain('if (sequence === loadSequence) error.value = safePageResourceError(reason)')
    expect(catalogView).toContain("'kind' in item ? item.status === 'ACTIVE' && !item.disabled : item.status === 'OPEN' && !item.disabled")
    expect(catalogView).not.toContain("item.status === 'PUBLISHED' && !item.disabled")
    expect(memberEditView).toContain("resourceType: 'MEMBER'")
    expect(memberEditView).toContain('platformApi.grantAssociationConsent({')
    expect(memberEditView).toContain('platformApi.revokeAssociationConsent(item)')
    expect(memberEditView).toContain('联系人、电话及统一社会信用代码始终不开放')
    expect(matchingView).toContain("resourceType: 'MATCH'")
    expect(matchingView).toContain('platformApi.grantAssociationConsent({')
    expect(matchingView).toContain('platformApi.revokeAssociationConsent(item)')
  })

  it('友好协会、知识文档和账号绑定均使用服务端分页入口', () => {
    const federation = readFileSync(join(sourceRoot, 'views', 'FederationView.vue'), 'utf8')
    const attachments = readFileSync(join(sourceRoot, 'views', 'AttachmentCenterView.vue'), 'utf8')
    const operations = readFileSync(join(sourceRoot, 'views', 'OperationsView.vue'), 'utf8')
    expect(federation).toContain('platformApi.associationAccessRequestPage(')
    expect(federation).toContain('platformApi.associationRecommendationPage(')
    expect(federation.match(/<PaginationBar/g)?.length).toBe(5)
    expect(attachments).toMatch(
      /platformApi\.knowledgeDocuments\(\s*includeDeletedKnowledge\.value,\s*knowledgePage\.value,\s*knowledgeSize\.value/,
    )
    expect(attachments).toContain(':page="knowledgePage"')
    expect(operations).toContain('platformApi.accessBindingPage(bindingPage.value, bindingSize.value)')
    expect(operations).toContain(':page="bindingPage"')
  })

  it('政策影响分析具备创建、详情、重分析、审核、历史和服务端分页入口', () => {
    const policiesView = readFileSync(join(sourceRoot, 'views', 'PoliciesView.vue'), 'utf8')
    expect(policiesView).toContain('platformApi.createPolicyImpact(')
    expect(policiesView).toContain('platformApi.policyImpact(item.id)')
    expect(policiesView).toContain('platformApi.reanalyzePolicyImpact(')
    expect(policiesView).toContain('platformApi.reviewPolicyImpact(')
    expect(policiesView).toContain('platformApi.policyImpactHistory(')
    expect(policiesView).toMatch(
      /platformApi\.members\(\s*impactMemberQuery\.value\.trim\(\),\s*'ACTIVE'/,
    )
    expect(policiesView).toContain(':disabled="impactBusy || !impactEnterpriseId"')
    expect(policiesView).toContain('v-if="canReviewHere && impactSelected.status === \'PENDING_REVIEW\'"')
    expect(policiesView).toContain('<PaginationBar :page="impactPageIndex"')
    expect(policiesView).toContain('<PaginationBar :page="impactMemberPage"')
  })
})

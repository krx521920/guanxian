import { expect, test, type Page } from '@playwright/test'
import {
  assetCard,
  authenticatedPage,
  e2eUsers,
  matchCard,
  openMatch,
  waitForApiWrite,
} from './support'

const supplierEnterpriseName = 'E2E北辰管线服务有限公司'

async function createAndSubmitOffering(page: Page, name: string): Promise<void> {
  await page.goto('/ecosystem')
  await page.getByRole('button', { name: '+ 新建产品/服务' }).click()
  const form = page.locator('form.modal-card')
  await form.getByLabel('名称 *').fill(name)
  await form.getByLabel('详细说明').fill('真实 E2E 管线监测、泄漏预警和现场联调服务。')
  await form.getByLabel('适用场景').fill('燃气管线\n智慧管网\n泄漏监测')
  await form.getByLabel('资质与证书').fill('E2E 自动化验收资质')
  await waitForApiWrite(page, /\/api\/v1\/offerings$/, async () => {
    await form.getByRole('button', { name: '保存草稿' }).click()
  })
  await expect(page.getByText('资料已保存，草稿可继续编辑或提交审核。')).toBeVisible()
  const card = assetCard(page, name)
  await expect(card).toHaveCount(1)
  await waitForApiWrite(page, /\/api\/v1\/offerings\/[0-9a-f-]+\/submit$/, async () => {
    await card.getByRole('button', { name: '提交审核' }).click()
  })
  await expect(card).toContainText('待审核')
}

async function createAndSubmitDemand(page: Page, title: string): Promise<void> {
  await page.goto('/ecosystem')
  await page.getByRole('button', { name: '+ 发布需求' }).click()
  const form = page.locator('form.modal-card')
  await form.getByLabel('需求标题 *').fill(title)
  await form.getByLabel('需求说明 *').fill('需要燃气管线监测、泄漏预警和现场联调能力。')
  await form.getByLabel('应用场景').fill('燃气管线\n智慧管网')
  await form.getByLabel('所需能力').fill('泄漏监测\n管线监测')
  await form.getByLabel('预算下限').fill('100000')
  await form.getByLabel('预算上限').fill('300000')
  await waitForApiWrite(page, /\/api\/v1\/demands$/, async () => {
    await form.getByRole('button', { name: '保存草稿' }).click()
  })
  await expect(page.getByText('资料已保存，草稿可继续编辑或提交审核。')).toBeVisible()
  const card = assetCard(page, title)
  await expect(card).toHaveCount(1)
  await waitForApiWrite(page, /\/api\/v1\/demands\/[0-9a-f-]+\/submit$/, async () => {
    await card.getByRole('button', { name: '提交审核' }).click()
  })
  await expect(card).toContainText('待审核')
}

async function reviewOffering(page: Page, name: string): Promise<void> {
  await page.goto('/ecosystem')
  const search = page.getByPlaceholder('搜索产品、企业或场景')
  await search.fill(name)
  const card = assetCard(page, name)
  await expect(card).toHaveCount(1)
  await waitForApiWrite(page, /\/api\/v1\/offerings\/[0-9a-f-]+\/review$/, async () => {
    await card.getByRole('button', { name: '通过' }).click()
  })
  await expect(card).toContainText('进行中')
}

async function reviewDemand(page: Page, title: string): Promise<void> {
  await page.goto('/ecosystem')
  await page.getByRole('button', { name: /合作需求/ }).click()
  const search = page.getByPlaceholder('搜索需求、企业或场景')
  await search.fill(title)
  const card = assetCard(page, title)
  await expect(card).toHaveCount(1)
  await waitForApiWrite(page, /\/api\/v1\/demands\/[0-9a-f-]+\/review$/, async () => {
    await card.getByRole('button', { name: '通过' }).click()
  })
  await expect(card).toContainText('已开放')
}

async function generateAndRecommend(page: Page, title: string): Promise<void> {
  await page.goto('/matching')
  await page.getByRole('button', { name: '生成新一轮匹配' }).click()
  const dialog = page.locator('form.modal-card')
  const option = dialog.locator('option').filter({ hasText: title })
  await expect(option).toHaveCount(1)
  await dialog.getByLabel('需求 *').selectOption(await option.getAttribute('value') || '')
  await waitForApiWrite(page, /\/api\/v1\/matches\/demand\/[0-9a-f-]+\/generate$/, async () => {
    await dialog.getByRole('button', { name: '生成并保存匹配' }).click()
  })
  await expect(page.getByText(/已为该需求生成 \d+ 条可追踪匹配/)).toBeVisible()
  const card = matchCard(page, title, supplierEnterpriseName)
  await expect(card).toHaveCount(1)
  await card.getByRole('button', { name: '查看匹配详情' }).click()
  await waitForApiWrite(page, /\/api\/v1\/matches\/[0-9a-f-]+\/recommend$/, async () => {
    await page.getByRole('button', { name: '协会推荐' }).click()
  })
  await expect(page.locator('.modal-message').getByText(
    '协会已将匹配定向推荐给企业。', { exact: true },
  )).toBeVisible()
}

async function confirmMatch(page: Page, title: string): Promise<void> {
  await openMatch(page, title, supplierEnterpriseName)
  await waitForApiWrite(page, /\/api\/v1\/matches\/[0-9a-f-]+\/confirm$/, async () => {
    await page.getByRole('button', { name: '确认本方意向' }).click()
  })
  await expect(page.locator('.modal-message').getByText(
    '企业已确认匹配，可进入洽谈与协作。', { exact: true },
  )).toBeVisible()
}

async function advanceNegotiation(page: Page, stage: string, summary: string): Promise<void> {
  const section = page.locator('.workflow-section').filter({ hasText: '洽谈进度' })
  const form = section.locator('form.workflow-form')
  await form.getByLabel('洽谈阶段 *').selectOption(stage)
  await form.getByLabel('进展摘要 *').fill(summary)
  await waitForApiWrite(page, /\/api\/v1\/matches\/[0-9a-f-]+\/negotiations$/, async () => {
    await form.getByRole('button', { name: '保存洽谈记录' }).click()
  })
  await expect(page.locator('.modal-message').getByText(
    '洽谈记录已保存。', { exact: true },
  )).toBeVisible()
}

async function submitSuccessfulFeedback(page: Page, title: string, comment: string): Promise<void> {
  await openMatch(page, title, supplierEnterpriseName)
  const section = page.locator('.workflow-section').filter({ hasText: '企业匹配反馈' })
  const form = section.locator('form.workflow-form')
  await form.getByLabel('评分').selectOption('5')
  await form.getByLabel('反馈说明').fill(comment)
  await waitForApiWrite(page, /\/api\/v1\/matches\/[0-9a-f-]+\/feedback$/, async () => {
    await form.getByRole('button', { name: '提交企业反馈' }).click()
  })
  await expect(page.locator('.modal-message').getByText(
    '匹配反馈已提交，系统已保存企业评价。', { exact: true },
  )).toBeVisible()
}

test('协会与两家企业通过真实页面完成匹配业务闭环', async ({ browser }) => {
  test.slow()
  const run = Date.now().toString(36)
  const offeringName = `E2E泄漏监测服务-${run}`
  const demandTitle = `E2E燃气管线监测需求-${run}`
  const invitationMessage = `E2E定向合作邀请-${run}`
  const outcomeTitle = `E2E管线监测合作成果-${run}`
  const association = await authenticatedPage(browser, e2eUsers.associationAdmin)
  const demandEnterprise = await authenticatedPage(browser, e2eUsers.enterpriseAdmin)
  const supplier = await authenticatedPage(browser, e2eUsers.supplierAdmin)

  try {
    await createAndSubmitOffering(supplier.page, offeringName)
    await createAndSubmitDemand(demandEnterprise.page, demandTitle)
    await reviewOffering(association.page, offeringName)
    await reviewDemand(association.page, demandTitle)
    await generateAndRecommend(association.page, demandTitle)

    await confirmMatch(demandEnterprise.page, demandTitle)
    await confirmMatch(supplier.page, demandTitle)

    await openMatch(association.page, demandTitle, supplierEnterpriseName)
    const invitationSection = association.page.locator('.workflow-section').filter({ hasText: '定向邀请与应答' })
    await invitationSection.getByLabel('邀请说明').fill(invitationMessage)
    await waitForApiWrite(association.page, /\/api\/v1\/matches\/[0-9a-f-]+\/invitations$/, async () => {
      await invitationSection.getByRole('button', { name: '发送定向邀请' }).click()
    })
    await expect(association.page.locator('.modal-message').getByText(
      '定向邀请已发送并保存。', { exact: true },
    )).toBeVisible()

    await openMatch(supplier.page, demandTitle, supplierEnterpriseName)
    const invitation = supplier.page.locator('.workflow-record').filter({ hasText: invitationMessage })
    await expect(invitation).toHaveCount(1)
    await waitForApiWrite(supplier.page, /\/api\/v1\/matches\/invitations\/[0-9a-f-]+\/respond$/, async () => {
      await invitation.getByRole('button', { name: '接受' }).click()
    })
    await expect(supplier.page.locator('.modal-message').getByText(
      '已接受邀请，可继续记录洽谈进展。', { exact: true },
    )).toBeVisible()

    await openMatch(association.page, demandTitle, supplierEnterpriseName)
    await advanceNegotiation(association.page, 'INITIAL_CONTACT', '已完成初次联系。')
    await advanceNegotiation(association.page, 'TECHNICAL_EXCHANGE', '已完成技术交流。')
    await advanceNegotiation(association.page, 'COMMERCIAL_NEGOTIATION', '已完成商务洽谈。')
    await advanceNegotiation(association.page, 'CONTRACTING', '合同正在审批。')
    await advanceNegotiation(association.page, 'CONTRACT_SIGNED', '双方合同已签署。')

    await submitSuccessfulFeedback(demandEnterprise.page, demandTitle, '需求方确认合作达成。')
    await submitSuccessfulFeedback(supplier.page, demandTitle, '供给方确认合作达成。')

    await openMatch(association.page, demandTitle, supplierEnterpriseName)
    const outcomeSection = association.page.locator('.workflow-section').filter({ hasText: '合作成果归档' })
    const outcomeForm = outcomeSection.locator('form.workflow-form')
    await outcomeForm.getByLabel('成果标题 *').fill(outcomeTitle)
    await outcomeForm.getByLabel('成果摘要 *').fill('双方已完成燃气管线监测项目签约并进入实施。')
    await outcomeForm.getByLabel('合同金额（元）').fill('260000')
    await waitForApiWrite(association.page, /\/api\/v1\/matches\/[0-9a-f-]+\/outcomes$/, async () => {
      await outcomeForm.getByRole('button', { name: '归档合作成果' }).click()
    })
    await expect(association.page.locator('.modal-message').getByText(
      '合作成果已归档。', { exact: true },
    )).toBeVisible()
    await expect(outcomeSection).toContainText(outcomeTitle)
    await expect(association.page.locator('.detail-grid')).toContainText('成果已归档')
  } finally {
    await Promise.all([
      association.context.close(),
      demandEnterprise.context.close(),
      supplier.context.close(),
    ])
  }
})

import { execFile } from 'node:child_process'
import { mkdtemp } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { promisify } from 'node:util'
import { expect, test } from '@playwright/test'
import { assetCard, authenticatedPage, e2eUsers, waitForApiWrite } from './support'

const execFileAsync = promisify(execFile)

test('协会通过官方 Excel 完成会员导入、预检和审核', async ({ browser }) => {
  const { context, page } = await authenticatedPage(browser, e2eUsers.associationAdmin)
  const run = Date.now().toString(36).toUpperCase().padStart(7, '0').slice(-7)
  const enterpriseName = `E2E调查导入企业-${run}`
  const creditCode = `91110000E2E${run}`
  const temporary = await mkdtemp(path.join(tmpdir(), 'guanxian-member-e2e-'))
  const templatePath = path.join(temporary, 'template.xlsx')
  const workbookPath = path.join(temporary, 'member.xlsx')
  try {
    await page.goto('/members?action=import')
    const downloadPromise = page.waitForEvent('download')
    await page.getByRole('button', { name: '下载调查模板' }).first().click()
    await (await downloadPromise).saveAs(templatePath)
    await execFileAsync('python', [
      path.resolve(process.cwd(), '../../tests/e2e/create_member_workbook.py'),
      '--template', templatePath, '--output', workbookPath,
      '--name', enterpriseName, '--credit-code', creditCode,
    ])

    await waitForApiWrite(page, /\/api\/v1\/members\/imports\/preview$/, async () => {
      await page.locator('input[type="file"]').setInputFiles(workbookPath)
    })
    await expect(page.getByText('预检完成：1 行可导入，0 行需修正。')).toBeVisible()
    await waitForApiWrite(page, /\/api\/v1\/members\/imports\/[0-9a-f-]+\/commit$/, async () => {
      await page.getByRole('button', { name: '确认导入 1 家' }).click()
    })
    await expect(page.getByText(/已导入 1 家企业，统一进入待审核/)).toBeVisible()
    await page.getByPlaceholder('搜索企业名称、业务角色或产品服务').fill(enterpriseName)
    const row = page.locator('table.member-table tbody tr').filter({ hasText: enterpriseName })
    await expect(row).toHaveCount(1)
    await expect(row).toContainText('待审核')
    await row.getByRole('link', { name: '审核' }).click()
    await page.getByLabel('审核意见').fill('E2E 核对来源文件和提交单位后通过。')
    await waitForApiWrite(page, /\/api\/v1\/members\/[0-9a-f-]+\/review$/, async () => {
      await page.getByRole('button', { name: '审核通过' }).click()
    })
    await expect(page.getByText('审核通过，企业资料已认证。')).toBeVisible()
  } finally {
    await context.close()
  }
})

test('企业对已审核产品完成跨协会逐资源授权和立即撤销', async ({ browser }) => {
  const run = Date.now().toString(36)
  const productName = `E2E跨协会监测产品-${run}`
  const enterprise = await authenticatedPage(browser, e2eUsers.enterpriseAdmin)
  const association = await authenticatedPage(browser, e2eUsers.associationAdmin)
  try {
    await enterprise.page.goto('/ecosystem')
    await enterprise.page.getByRole('button', { name: '+ 新建产品/服务' }).click()
    const form = enterprise.page.locator('form.modal-card')
    await form.getByLabel('名称 *').fill(productName)
    await form.getByLabel('详细说明').fill('用于跨协会协作验收的真实持久化产品。')
    await form.getByLabel('可见范围').selectOption('PARTNERS')
    await waitForApiWrite(enterprise.page, /\/api\/v1\/offerings$/, async () => {
      await form.getByRole('button', { name: '保存草稿' }).click()
    })
    const enterpriseCard = assetCard(enterprise.page, productName)
    await waitForApiWrite(enterprise.page, /\/api\/v1\/offerings\/[0-9a-f-]+\/submit$/, async () => {
      await enterpriseCard.getByRole('button', { name: '提交审核' }).click()
    })

    await association.page.goto('/ecosystem')
    await association.page.getByPlaceholder('搜索产品、企业或场景').fill(productName)
    const reviewCard = assetCard(association.page, productName)
    await waitForApiWrite(association.page, /\/api\/v1\/offerings\/[0-9a-f-]+\/review$/, async () => {
      await reviewCard.getByRole('button', { name: '通过' }).click()
    })

    await enterprise.page.reload()
    await enterprise.page.getByPlaceholder('搜索产品、企业或场景').fill(productName)
    const approvedCard = assetCard(enterprise.page, productName)
    await approvedCard.getByRole('button', { name: '跨协会授权' }).click()
    const consentModal = enterprise.page.locator('.modal-card').filter({ hasText: '跨协会逐资源授权' })
    await expect(consentModal.getByLabel('目标协会 *')).toHaveValue('00000000-0000-0000-0000-000000000107')
    await waitForApiWrite(enterprise.page, /\/api\/v1\/cross-associations\/consents$/, async () => {
      await consentModal.getByRole('button', { name: '确认授权' }).click()
    })
    await expect(enterprise.page.getByText(/企业逐资源授权已生效/)).toBeVisible()
    await waitForApiWrite(enterprise.page, /\/api\/v1\/cross-associations\/consents\/[0-9a-f-]+$/, async () => {
      await consentModal.getByRole('button', { name: '撤销' }).click()
    })
    await expect(enterprise.page.getByText('该资源的定向共享授权已撤销。')).toBeVisible()
  } finally {
    await Promise.all([enterprise.context.close(), association.context.close()])
  }
})

test('协会上传原文、审核知识文档并完成带可下载出处的问答', async ({ browser }) => {
  const { context, page } = await authenticatedPage(browser, e2eUsers.associationAdmin)
  const run = Date.now().toString(36)
  const filename = `e2e-policy-evidence-${run}.txt`
  const title = `E2E巡检制度-${run}`
  const evidence = Buffer.from(`资料编号 ${run}：地下管线阀门应当每七天完成一次巡检并留存记录。\n`, 'utf8')
  try {
    await page.goto('/attachments')
    await waitForApiWrite(page, /\/api\/v1\/attachments$/, async () => {
      await page.locator('input[type="file"]').setInputFiles({ name: filename, mimeType: 'text/plain', buffer: evidence })
    })
    const attachmentRow = page.locator('tr').filter({ hasText: filename })
    await attachmentRow.getByRole('button', { name: '纳入知识库' }).click()
    const form = page.locator('form.modal-card').filter({ hasText: '创建知识草稿' })
    await form.getByLabel('资料标题 *').fill(title)
    await waitForApiWrite(page, /\/api\/v1\/knowledge\/documents\/file$/, async () => {
      await form.getByRole('button', { name: '创建草稿' }).click()
    })
    const knowledgeRow = page.locator('tr').filter({ hasText: title })
    await waitForApiWrite(page, /\/api\/v1\/knowledge\/documents\/[0-9a-f-]+\/submit$/, async () => {
      await knowledgeRow.getByRole('button', { name: '提交审核' }).click()
    })
    await waitForApiWrite(page, /\/api\/v1\/knowledge\/documents\/[0-9a-f-]+\/review$/, async () => {
      await knowledgeRow.getByRole('button', { name: '审核通过' }).click()
    })

    await page.goto('/policies')
    await page.getByLabel('请输入政策、标准或协会资料问题').fill(`资料编号 ${run} 规定阀门多久巡检一次？`)
    await waitForApiWrite(page, /\/api\/v1\/knowledge\/questions$/, async () => {
      await page.getByRole('button', { name: '查询资料' }).click()
    })
    const answer = page.locator('article.modal-copy').filter({ hasText: '追踪编号' })
    await expect(answer).toContainText(title)
    await expect(answer).toContainText('每七天')
    const citation = answer.locator('.impact-list > article').filter({ hasText: title })
    await expect(citation).toHaveCount(1)
    const downloadPromise = page.waitForEvent('download')
    await citation.getByRole('button', { name: '下载原始附件 ↓' }).click()
    const download = await downloadPromise
    const stream = await download.createReadStream()
    const chunks: Buffer[] = []
    for await (const chunk of stream) chunks.push(Buffer.from(chunk))
    expect(Buffer.concat(chunks)).toEqual(evidence)
  } finally {
    await context.close()
  }
})

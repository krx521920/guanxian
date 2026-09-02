import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { createLatestRequestGate } from '../services/latest-request'

const source = readFileSync(
  fileURLToPath(new URL('./PoliciesView.vue', import.meta.url)),
  'utf8',
)

function section(start: string, end: string) {
  return source.slice(source.indexOf(start), source.indexOf(end))
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise
  })
  return { promise, resolve }
}

describe('政策页面请求状态真实性', () => {
  it('政策列表和影响列表使用相互独立的 latest-request gate', () => {
    const policyLoad = section('async function load()', 'async function loadImpacts()')
    const impactLoad = section('async function loadImpacts()', 'function payload()')

    expect(source).toContain('const policyListRequestGate = createLatestRequestGate()')
    expect(source).toContain('const impactListRequestGate = createLatestRequestGate()')
    expect(policyLoad).toContain('const requestEpoch = policyListRequestGate.begin()')
    expect(policyLoad).toContain('if (!policyListRequestGate.isCurrent(requestEpoch)) return')
    expect(policyLoad).toContain('if (policyListRequestGate.isCurrent(requestEpoch))')
    expect(impactLoad).toContain('const requestEpoch = impactListRequestGate.begin()')
    expect(impactLoad).toContain('if (!impactListRequestGate.isCurrent(requestEpoch)) return')
    expect(impactLoad).toContain('if (impactListRequestGate.isCurrent(requestEpoch))')
  })

  it('后发筛选或分页请求胜出，关闭详情会让未完成请求失效', async () => {
    const listGate = createLatestRequestGate()
    const oldPage = deferred<string>()
    const newFilter = deferred<string>()
    let committed = ''

    async function commitLatest(request: Promise<string>) {
      const epoch = listGate.begin()
      const result = await request
      if (listGate.isCurrent(epoch)) committed = result
    }

    const oldRun = commitLatest(oldPage.promise)
    const newRun = commitLatest(newFilter.promise)
    newFilter.resolve('待审核第一页')
    await newRun
    oldPage.resolve('全部状态第三页')
    await oldRun
    expect(committed).toBe('待审核第一页')

    const detailGate = createLatestRequestGate()
    const detailEpoch = detailGate.begin()
    detailGate.invalidate()
    expect(detailGate.isCurrent(detailEpoch)).toBe(false)
    expect(section('function closeImpactDetail()', 'watch(keyword')).toContain(
      'impactDetailRequestGate.invalidate()',
    )
  })

  it('影响列表初次加载有独立 loading，不会先显示空结果', () => {
    expect(source).toContain('const impactLoading = ref(true)')
    expect(source).toContain('impactLoading.value = true')
    expect(source).toContain('impactLoading.value = false')
    expect(source).toContain('v-if="impactLoading">正在加载影响分析…</h2>')
    expect(source).toContain('v-if="impactLoading" class="empty-business-state"><b>正在加载影响分析…</b>')
    expect(source).toContain(':disabled="impactLoading || impactBusy"')
  })

  it('详情失败清空详情和历史，操作按钮只能基于重新加载的详情出现', () => {
    const detailLoad = section('async function openImpact(', 'async function reanalyzeImpact()')
    const detailReset = section('function clearImpactDetailState()', 'async function openImpact(')

    expect(source).toContain('const impactDetailRequestGate = createLatestRequestGate()')
    expect(detailReset).toContain('impactSelected.value = null')
    expect(detailReset).toContain('impactHistories.value = []')
    expect(detailLoad).toContain('clearImpactDetailState()')
    expect(detailLoad).toContain('impactDetailRequestGate.isCurrent(requestEpoch)')
    expect(source).toContain('<template v-else-if="impactSelected">')
    expect(source).toContain('v-if="canReviewHere && impactSelected.status === \'PENDING_REVIEW\'"')
  })

  it('停用政策和已删除政策都有显式恢复为草稿入口', () => {
    expect(source).toContain(
      'v-if="canReviewHere && !selected.deleted && selected.status === \'DISABLED\'"',
    )
    expect(source).toContain('@click="restorePolicy(selected)">恢复为草稿</button>')
    expect(source).toContain(
      'v-if="canReviewHere && selected.deleted" class="primary-button" type="button" :disabled="busy" @click="restorePolicy(selected)">恢复为草稿</button>',
    )
  })
})

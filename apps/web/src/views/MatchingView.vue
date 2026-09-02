<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import PageHeader from '../components/PageHeader.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { safePageResourceError, useAsyncResource, type PageResourceError } from '../composables/useAsyncResource'
import { useAuth } from '../services/auth'
import { platformApi } from '../services/platform-api'
import type { EcosystemDemand, PersistedEcosystemMatch } from '../types/domain'
import {
  MATCH_FILTERS,
  canGenerateMatches,
  displayText,
  filterMatches,
  formatMatchTime,
  loadOpenDemands,
  normalizedScore,
  scoreDashOffset,
  summarizeMatches,
} from './matching-view'

const auth = useAuth()
const { data: items, loading, error, load } = useAsyncResource(platformApi.matches)
const selectedState = ref('ALL')
const ruleOpen = ref(false)
const detail = ref<PersistedEcosystemMatch | null>(null)
const generationOpen = ref(false)
const generationLoading = ref(false)
const generationSubmitting = ref(false)
const generationError = ref<PageResourceError | null>(null)
const generationSubmitError = ref<PageResourceError | null>(null)
const generationMessage = ref<string | null>(null)
const openDemands = ref<EcosystemDemand[]>([])
const selectedDemandId = ref('')
const generationLimit = ref(5)

const visibleItems = computed(() => items.value ?? [])
const filtered = computed(() => filterMatches(visibleItems.value, selectedState.value))
const summary = computed(() => summarizeMatches(visibleItems.value))
const canGenerate = computed(() => canGenerateMatches(auth.user.value))

async function loadGenerationDemands() {
  generationLoading.value = true
  generationError.value = null
  generationSubmitError.value = null
  generationMessage.value = null
  try {
    const result = await loadOpenDemands((page, size) => platformApi.matchingDemands(page, size))
    openDemands.value = result
    if (!result.some((item) => item.id === selectedDemandId.value)) {
      selectedDemandId.value = result[0]?.id ?? ''
    }
  } catch (reason: unknown) {
    openDemands.value = []
    selectedDemandId.value = ''
    generationError.value = safePageResourceError(reason)
  } finally {
    generationLoading.value = false
  }
}

async function openGeneration() {
  if (!canGenerate.value) return
  generationOpen.value = true
  await loadGenerationDemands()
}

async function generateMatches() {
  if (!selectedDemandId.value || generationSubmitting.value) return
  generationSubmitting.value = true
  generationSubmitError.value = null
  generationMessage.value = null
  try {
    const generated = await platformApi.generateMatches(selectedDemandId.value, generationLimit.value)
    generationMessage.value = generated.length > 0
      ? `服务端已生成或刷新 ${generated.length} 条匹配记录。`
      : '服务端已完成本轮计算，当前没有返回候选记录。'
    await load()
  } catch (reason: unknown) {
    generationSubmitError.value = safePageResourceError(reason)
  } finally {
    generationSubmitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="matching-page">
    <PageHeader title="生态匹配" description="展示服务端按当前身份授权返回的持久化供需匹配记录">
      <button class="secondary-button" type="button" @click="ruleOpen = true">匹配规则</button>
      <button class="secondary-button" type="button" :disabled="loading" @click="load">刷新记录</button>
      <button class="primary-button" type="button" :disabled="!canGenerate || loading" :title="canGenerate ? '从开放需求生成匹配记录' : '当前身份没有生成匹配所需的写入权限'" @click="openGeneration">生成匹配</button>
    </PageHeader>

    <section class="match-summary" aria-label="当前可见匹配摘要">
      <div><span>可见匹配记录</span><strong>{{ summary.total }}</strong><small>以本次接口返回为准</small></div>
      <div><span>待确认或已推荐</span><strong>{{ summary.awaitingConfirmation }}</strong><small>等待业务方继续处理</small></div>
      <div><span>已确认 / 已关闭</span><strong>{{ summary.confirmed }} / {{ summary.closed }}</strong><small>按持久化状态统计</small></div>
      <div class="matching-logic"><span class="rule-chip">规则</span><p><b>数据口径</b>数量和得分均取自真实接口；本页不使用演示记录，也不把规则计算包装成模型能力。</p></div>
    </section>

    <div class="segmented match-tabs" aria-label="匹配状态筛选">
      <button v-for="filter in MATCH_FILTERS" :key="filter.value" type="button" :class="{ active: selectedState === filter.value }" @click="selectedState = filter.value">{{ filter.label }}</button>
    </div>

    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <section v-else-if="visibleItems.length === 0" class="resource-error panel" aria-live="polite">
      <span class="resource-error-icon" aria-hidden="true">○</span><h2>当前范围暂无匹配记录</h2>
      <p>接口返回了真实空列表。需要先建立并开放合作需求，具备写入权限的用户才能发起匹配。</p>
      <button v-if="canGenerate" class="primary-button" type="button" @click="openGeneration">检查开放需求</button>
      <RouterLink v-else class="primary-button" to="/ecosystem">返回生态概览</RouterLink>
    </section>
    <section v-else-if="filtered.length === 0" class="resource-error panel" aria-live="polite">
      <span class="resource-error-icon" aria-hidden="true">○</span><h2>该状态暂无记录</h2><p>当前可见记录中没有符合所选状态的数据。</p>
      <button class="primary-button" type="button" @click="selectedState = 'ALL'">查看全部</button>
    </section>
    <section v-else class="match-list">
      <article v-for="item in filtered" :key="item.id" class="match-card panel">
        <div class="match-score"><svg viewBox="0 0 44 44" aria-hidden="true"><circle cx="22" cy="22" r="18"/><circle class="score-line" cx="22" cy="22" r="18" :style="{ strokeDashoffset: scoreDashOffset(item.score) }"/></svg><div><strong>{{ normalizedScore(item.score) }}</strong><span>规则得分</span></div></div>
        <div class="match-demand"><span class="eyebrow">需求方 · {{ displayText(item.scene, '场景未填写') }}</span><h2>{{ item.demandTitle }}</h2><p>{{ displayText(item.demandCompany, '需求方名称未授权展示') }}</p></div>
        <div class="match-arrow"><span>规则匹配</span>→</div>
        <div class="match-supplier"><span class="eyebrow">候选供给方</span><h2>{{ displayText(item.supplierCompany, '供给方名称未授权展示') }}</h2><p>{{ displayText(item.solution, '服务端暂未提供方案摘要') }}</p></div>
        <div class="match-actions"><StatusBadge :value="item.state"/><small>{{ formatMatchTime(item.updatedAt) }}</small><button class="primary-button small" type="button" @click="detail = item">查看匹配详情</button></div>
        <div class="match-reasons"><b>匹配说明</b><span v-for="reason in item.reasons" :key="reason">{{ reason }}</span><span v-if="item.reasons.length === 0">服务端未提供匹配说明</span></div>
      </article>
    </section>

    <Teleport to="body">
      <div v-if="ruleOpen" class="dialog-backdrop" @click.self="ruleOpen = false" @keydown.esc="ruleOpen = false">
        <section class="member-create-dialog matching-dialog" role="dialog" aria-modal="true" aria-labelledby="match-rule-title" tabindex="-1">
          <header class="dialog-header"><div><h2 id="match-rule-title">当前匹配规则说明</h2><p>说明页面实际调用的服务端流程与前置条件</p></div><button class="dialog-close" type="button" aria-label="关闭匹配规则" @click="ruleOpen = false">×</button></header>
          <div class="dialog-body matching-dialog-body">
            <ol><li>只有状态为“开放中”的合作需求可以生成匹配。</li><li>服务端会校验当前用户的写入权限、企业归属和协会数据范围。</li><li>候选得分来自当前服务端的确定性规则，依据需求场景、能力关键词和可见会员资料计算。</li><li>生成结果会写入匹配仓储；刷新后只展示当前身份有权查看的持久化记录。</li></ol>
            <p>本页当前没有调用大模型接口，因此不标注智能生成或模型推荐。</p>
            <div class="form-actions"><button class="primary-button" type="button" @click="ruleOpen = false">知道了</button></div>
          </div>
        </section>
      </div>

      <div v-if="generationOpen" class="dialog-backdrop" @click.self="generationOpen = false" @keydown.esc="generationOpen = false">
        <section class="member-create-dialog matching-dialog" role="dialog" aria-modal="true" aria-labelledby="match-generate-title" tabindex="-1">
          <header class="dialog-header"><div><h2 id="match-generate-title">从开放需求生成匹配</h2><p>只有服务端判定有权处理的需求才能成功生成</p></div><button class="dialog-close" type="button" aria-label="关闭生成匹配" @click="generationOpen = false">×</button></header>
          <div class="dialog-body matching-dialog-body">
            <AsyncResourceState v-if="generationLoading || generationError" :loading="generationLoading" :error="generationError" @retry="loadGenerationDemands"/>
            <template v-else>
              <section v-if="openDemands.length === 0" class="resource-error" aria-live="polite"><h2>暂无可选择的开放需求</h2><p>当前账号可见的需求中，没有状态为“开放中”且未停用的记录。</p></section>
              <div v-else class="matching-generation-form">
                <label><span>开放需求</span><select v-model="selectedDemandId"><option v-for="demand in openDemands" :key="demand.id" :value="demand.id">{{ demand.title }} · {{ displayText(demand.enterpriseName, '企业名称未展示') }}</option></select></label>
                <label><span>候选数量</span><input v-model.number="generationLimit" type="number" min="1" max="20" step="1"/></label>
                <p>提交后由服务端再次校验需求状态、企业归属和协会权限；接口拒绝时页面不会伪造生成结果。</p>
              </div>
              <section v-if="generationSubmitError" class="matching-inline-message matching-inline-error" role="alert"><strong>生成失败</strong><span>{{ generationSubmitError.message }}</span><small v-if="generationSubmitError.requestId">请求编号：{{ generationSubmitError.requestId }}</small></section>
              <section v-if="generationMessage" class="matching-inline-message" aria-live="polite"><strong>生成请求已完成</strong><span>{{ generationMessage }}</span></section>
            </template>
            <div class="form-actions"><button class="secondary-button" type="button" @click="generationOpen = false">关闭</button><button class="primary-button" type="button" :disabled="generationLoading || generationSubmitting || !selectedDemandId" @click="generateMatches">{{ generationSubmitting ? '生成中…' : '提交生成' }}</button></div>
          </div>
        </section>
      </div>

      <div v-if="detail" class="dialog-backdrop" @click.self="detail = null" @keydown.esc="detail = null">
        <section class="member-create-dialog matching-dialog" role="dialog" aria-modal="true" aria-labelledby="match-detail-title" tabindex="-1">
          <header class="dialog-header"><div><h2 id="match-detail-title">匹配详情</h2><p>记录编号：{{ detail.id }}</p></div><button class="dialog-close" type="button" aria-label="关闭匹配详情" @click="detail = null">×</button></header>
          <div class="dialog-body matching-dialog-body">
            <dl class="matching-detail-grid"><div><dt>合作需求</dt><dd>{{ detail.demandTitle }}</dd></div><div><dt>需求方</dt><dd>{{ displayText(detail.demandCompany, '名称未授权展示') }}</dd></div><div><dt>候选供给方</dt><dd>{{ displayText(detail.supplierCompany, '名称未授权展示') }}</dd></div><div><dt>匹配状态</dt><dd><StatusBadge :value="detail.state"/></dd></div><div><dt>场景</dt><dd>{{ displayText(detail.scene, '未填写') }}</dd></div><div><dt>规则得分</dt><dd>{{ normalizedScore(detail.score) }}</dd></div><div><dt>方案摘要</dt><dd>{{ displayText(detail.solution, '未提供') }}</dd></div><div><dt>更新时间</dt><dd>{{ formatMatchTime(detail.updatedAt) }}</dd></div><div><dt>记录版本</dt><dd>{{ detail.version }}</dd></div><div v-if="detail.closedReason"><dt>关闭原因</dt><dd>{{ detail.closedReason }}</dd></div></dl>
            <section class="matching-detail-reasons"><h3>匹配说明</h3><ul v-if="detail.reasons.length"><li v-for="reason in detail.reasons" :key="reason">{{ reason }}</li></ul><p v-else>服务端未提供匹配说明。</p></section>
            <div class="form-actions"><button class="secondary-button" type="button" @click="detail = null">关闭</button><RouterLink class="primary-button" to="/collaborations" @click="detail = null">进入协作事项</RouterLink></div>
          </div>
        </section>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.rule-chip { min-width: 42px; height: 32px; padding: 0 8px; border-radius: 8px; color: #fff; background: var(--primary); display: grid; place-items: center; font-size: 10px; font-weight: 700; }
.matching-dialog { width: min(720px, calc(100vw - 64px)); }
.matching-dialog-body { padding: 24px; }
.matching-dialog-body > ol { margin: 0 0 18px; padding-left: 22px; color: var(--ink); font-size: 13px; line-height: 1.8; }
.matching-dialog-body > p { color: var(--muted); font-size: 12px; line-height: 1.7; }
.matching-dialog-body .form-actions { position: static; margin: 22px -24px -24px; justify-content: flex-end; }
.matching-generation-form { display: grid; gap: 16px; }
.matching-generation-form label { display: grid; gap: 7px; color: var(--ink); font-size: 12px; font-weight: 650; }
.matching-generation-form select, .matching-generation-form input { width: 100%; min-height: 42px; padding: 0 12px; border: 1px solid var(--line); border-radius: 8px; color: var(--ink); background: var(--panel); }
.matching-generation-form p { margin: 0; color: var(--muted); font-size: 12px; line-height: 1.7; }
.matching-inline-message { margin-top: 16px; padding: 12px 14px; border-radius: 8px; color: var(--primary-dark); background: var(--primary-soft); display: grid; gap: 4px; font-size: 12px; }
.matching-inline-error { color: #a13d34; background: #fff0ee; }
.matching-inline-message small { color: inherit; opacity: .8; }
.matching-detail-grid { margin: 0; display: grid; grid-template-columns: 1fr 1fr; gap: 14px 20px; }
.matching-detail-grid div { padding-bottom: 10px; border-bottom: 1px solid var(--line); }
.matching-detail-grid dt { margin-bottom: 5px; color: var(--muted); font-size: 11px; }
.matching-detail-grid dd { margin: 0; color: var(--ink); font-size: 13px; overflow-wrap: anywhere; }
.matching-detail-reasons { margin-top: 20px; }
.matching-detail-reasons h3 { margin: 0 0 10px; font-size: 14px; }
.matching-detail-reasons ul { margin: 0; padding-left: 20px; color: var(--muted); font-size: 12px; line-height: 1.8; }
@media (max-width: 560px) { .matching-dialog { width: 100%; } .matching-dialog-body { padding: 18px 16px; } .matching-dialog-body .form-actions { margin: 18px -16px -18px; } .matching-detail-grid { grid-template-columns: 1fr; } }
</style>

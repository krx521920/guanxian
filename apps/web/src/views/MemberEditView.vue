<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import PageHeader from '../components/PageHeader.vue'
import ProfileWorkflowPanel from '../components/ProfileWorkflowPanel.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { safePageResourceError, type PageResourceError } from '../composables/useAsyncResource'
import { useAuth } from '../services/auth'
import { ApiRequestError } from '../services/http'
import { platformApi } from '../services/platform-api'
import type {
  AssociationConsent,
  AssociationConsentTarget,
  MemberStatus,
  MemberUpsertPayload,
  MemberVisibility,
  VersionedMember,
} from '../types/domain'
import { displayBusinessStatus, formatDateTime } from './business-form'

const route = useRoute()
const auth = useAuth()
const memberId = String(route.params.id)
const versioned = ref<VersionedMember | null>(null)
const loading = ref(false)
const loadError = ref<PageResourceError | null>(null)
const saving = ref(false)
const reviewing = ref(false)
const reviewComment = ref('')
const saveMessage = ref<string | null>(null)
const conflict = ref(false)
const canReview = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN'].includes(auth.user.value?.role || ''))
const canSetStatus = canReview
const canSetVisibility = computed(() => auth.user.value?.role !== 'ENTERPRISE_ADMIN')
const memberConsents = ref<AssociationConsent[]>([])
const memberConsentTargets = ref<AssociationConsentTarget[]>([])
const consentBusy = ref(false)
const consentForm = reactive({ targetAssociationId: '', expiresAt: '' })
const canManageMemberConsent = computed(() =>
  ['SYSTEM_ADMIN', 'ENTERPRISE_ADMIN'].includes(auth.user.value?.role || '')
    && auth.user.value?.enterpriseId === memberId)
const memberShareReady = computed(() => versioned.value?.member.status === 'ACTIVE'
  && versioned.value.member.visibility === 'PARTNERS')
const memberConsentHistory = computed(() => memberConsents.value.filter((consent) =>
  consent.resourceType === 'MEMBER' && consent.resourceId === memberId))
const activeMemberConsents = computed(() => memberConsentHistory.value.filter(isConsentActive))
const grantableMemberTargets = computed(() => memberConsentTargets.value
  .filter((target) => target.resourceType === 'MEMBER')
  .filter((target) => !activeMemberConsents.value.some((consent) =>
    consent.targetAssociationId === target.targetAssociationId)))
const selectedMemberTarget = computed(() => memberConsentTargets.value.find((target) =>
  target.resourceType === 'MEMBER' && target.targetAssociationId === consentForm.targetAssociationId))
const minimumConsentExpiry = localDateTime(new Date(Date.now() + 60_000))
const maximumConsentExpiry = computed(() => localDateTime(selectedMemberTarget.value?.policyExpiresAt))

const form = reactive({
  name: '', unifiedSocialCreditCode: '', category: '', address: '', contactName: '', contactPhone: '', contactEmail: '',
  introduction: '', capabilities: '', products: '', services: '', applicationScenarios: '', cooperationNeeds: '',
  visibility: 'MEMBERS' as MemberVisibility, status: 'ACTIVE' as MemberStatus,
})

function fillForm(value: VersionedMember) {
  const member = value.member
  form.name = member.name
  form.unifiedSocialCreditCode = member.unifiedSocialCreditCode || ''
  form.category = member.category
  form.address = member.address || ''
  form.contactName = member.contactName || ''
  form.contactPhone = member.contactPhone || ''
  form.contactEmail = member.contactEmail || ''
  form.introduction = member.introduction || ''
  form.capabilities = member.capabilities.join('\n')
  form.products = member.products.join('\n')
  form.services = (member.services || []).join('\n')
  form.applicationScenarios = (member.applicationScenarios || []).join('\n')
  form.cooperationNeeds = member.cooperationNeeds.join('\n')
  form.visibility = member.visibility
  form.status = member.status
}
function listValue(value: string): string[] { return [...new Set(value.split(/[\n,，、;；]+/).map((item) => item.trim()).filter(Boolean))] }
function nullable(value: string): string | null { return value.trim() || null }
function payload(): MemberUpsertPayload {
  return {
    name: form.name.trim(), unifiedSocialCreditCode: nullable(form.unifiedSocialCreditCode), category: form.category.trim(),
    address: nullable(form.address), contactName: nullable(form.contactName), contactPhone: nullable(form.contactPhone),
    contactEmail: nullable(form.contactEmail),
    introduction: nullable(form.introduction), capabilities: listValue(form.capabilities), products: listValue(form.products),
    services: listValue(form.services), applicationScenarios: listValue(form.applicationScenarios),
    cooperationNeeds: listValue(form.cooperationNeeds), visibility: form.visibility, status: form.status,
  }
}
function localDateTime(value: string | Date | null | undefined): string {
  if (!value) return ''
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 16)
}
function isConsentActive(item: AssociationConsent): boolean {
  return item.status === 'ACTIVE' && !item.revokedAt
    && (!item.expiresAt || new Date(item.expiresAt).getTime() > Date.now())
}
function defaultConsentExpiry(target: AssociationConsentTarget): string {
  const oneYear = Date.now() + 365 * 86_400_000
  const policyEnd = target.policyExpiresAt ? new Date(target.policyExpiresAt).getTime() : Number.POSITIVE_INFINITY
  return localDateTime(new Date(Math.min(oneYear, policyEnd)))
}
function selectConsentTarget() {
  const target = selectedMemberTarget.value
  consentForm.expiresAt = target ? defaultConsentExpiry(target) : ''
}
async function loadMemberConsentContext() {
  if (!canManageMemberConsent.value) return
  try {
    [memberConsents.value, memberConsentTargets.value] = await Promise.all([
      platformApi.associationConsents(),
      platformApi.associationConsentTargets(),
    ])
    consentForm.targetAssociationId = grantableMemberTargets.value[0]?.targetAssociationId || ''
    selectConsentTarget()
  } catch {
    saveMessage.value = '企业资料已加载，但跨协会授权上下文暂时无法读取。'
  }
}
async function grantMemberConsent() {
  const target = selectedMemberTarget.value
  if (!target || consentBusy.value || !memberShareReady.value) return
  const expiresAtDate = new Date(consentForm.expiresAt)
  if (Number.isNaN(expiresAtDate.getTime()) || expiresAtDate.getTime() <= Date.now()) {
    saveMessage.value = '跨协会授权必须设置未来的截止时间。'
    return
  }
  if (target.policyExpiresAt && expiresAtDate.getTime() > new Date(target.policyExpiresAt).getTime()) {
    saveMessage.value = '企业授权截止时间不能晚于协会字段策略截止时间。'
    return
  }
  consentBusy.value = true
  saveMessage.value = null
  try {
    const saved = await platformApi.grantAssociationConsent({
      enterpriseId: null,
      targetAssociationId: target.targetAssociationId,
      resourceType: 'MEMBER',
      resourceId: memberId,
      expiresAt: expiresAtDate.toISOString(),
    })
    memberConsents.value = [saved, ...memberConsents.value]
    consentForm.targetAssociationId = grantableMemberTargets.value[0]?.targetAssociationId || ''
    selectConsentTarget()
    saveMessage.value = '企业资料的跨协会字段授权已生效；联系人和统一信用代码始终不会通过该授权开放。'
  } catch {
    saveMessage.value = '跨协会授权失败，请刷新后确认关系和字段策略仍然有效。'
  } finally {
    consentBusy.value = false
  }
}
async function revokeMemberConsent(item: AssociationConsent) {
  if (consentBusy.value || !isConsentActive(item)) return
  consentBusy.value = true
  saveMessage.value = null
  try {
    const saved = await platformApi.revokeAssociationConsent(item)
    memberConsents.value = memberConsents.value.map((value) => value.id === saved.id ? saved : value)
    if (!consentForm.targetAssociationId) {
      consentForm.targetAssociationId = grantableMemberTargets.value[0]?.targetAssociationId || ''
      selectConsentTarget()
    }
    saveMessage.value = '企业资料的定向共享授权已撤销。'
  } catch {
    saveMessage.value = '撤销跨协会授权失败，请稍后重试。'
  } finally {
    consentBusy.value = false
  }
}
async function load() {
  if (loading.value) return
  loading.value = true; loadError.value = null; saveMessage.value = null; conflict.value = false
  try {
    const result = await platformApi.member(memberId)
    versioned.value = result
    fillForm(result)
    await loadMemberConsentContext()
  }
  catch (reason) { loadError.value = safePageResourceError(reason) }
  finally { loading.value = false }
}
async function save() {
  if (!versioned.value || saving.value) return
  saving.value = true; saveMessage.value = null; conflict.value = false
  try {
    const result = await platformApi.updateMember(memberId, payload(), versioned.value.etag)
    versioned.value = result; fillForm(result)
    saveMessage.value = result.member.status === 'PENDING_REVIEW' ? '企业资料已保存并进入待审核。' : '企业资料已保存。'
  } catch (reason) {
    if (reason instanceof ApiRequestError && reason.status === 412) { conflict.value = true; saveMessage.value = '保存失败：资料已被其他用户更新，请重新加载最新版本后再修改。' }
    else if (reason instanceof ApiRequestError && reason.status === 403) saveMessage.value = '保存失败：只能维护当前账号绑定的企业资料。'
    else saveMessage.value = '保存失败，请稍后重试。'
  } finally { saving.value = false }
}
async function review(decision: 'ACTIVE' | 'INCOMPLETE' | 'DISABLED') {
  if (!versioned.value || reviewing.value) return
  reviewing.value = true; saveMessage.value = null; conflict.value = false
  try {
    const result = await platformApi.reviewMember(memberId, decision, reviewComment.value, versioned.value.etag)
    versioned.value = result; fillForm(result); reviewComment.value = ''
    saveMessage.value = decision === 'ACTIVE' ? '审核通过，企业资料已认证。' : (decision === 'INCOMPLETE' ? '已退回企业补充资料。' : '企业资料已停用。')
  } catch (reason) {
    if (reason instanceof ApiRequestError && reason.status === 412) { conflict.value = true; saveMessage.value = '审核失败：资料版本已变化，请重新加载。' }
    else saveMessage.value = '审核失败，请稍后重试。'
  } finally { reviewing.value = false }
}
onMounted(load)
</script>

<template>
  <div>
    <PageHeader eyebrow="MEMBER PROFILE" title="编辑会员企业" description="普通资料修改走草稿、独立审核与发布；下方管理主档仅供身份和生命周期核验">
      <RouterLink class="secondary-button" to="/members">返回会员列表</RouterLink>
    </PageHeader>
    <AsyncResourceState v-if="loading || loadError" :loading="loading" :error="loadError" @retry="load" />
    <ProfileWorkflowPanel v-if="versioned" :enterprise-id="memberId" @approved="load" />
    <details v-if="versioned && canSetStatus" class="panel">
    <summary class="admin-master-summary">管理员主档核验（身份、共享范围与可用状态）</summary>
    <p class="admin-master-summary">日常资料修改请使用上方草稿流程。此处保留受审计的管理员主档维护，不会更新游客公开快照；主档版本变化会阻止旧稿覆盖。</p>
    <form class="member-edit-form" @submit.prevent="save">
      <div class="edit-version-bar"><span>当前数据版本：{{ versioned.member.version }} · 数据范围：{{ versioned.member.visibility }}</span><button class="text-button" type="button" @click="load">重新加载最新版本</button></div>
      <section class="form-section"><h3>企业基础信息</h3><div class="form-grid">
        <label><span>企业名称 *</span><input v-model="form.name" :disabled="auth.user.value?.role === 'ENTERPRISE_ADMIN'" required maxlength="200" /></label>
        <label><span>统一社会信用代码</span><input v-model="form.unifiedSocialCreditCode" :disabled="auth.user.value?.role === 'ENTERPRISE_ADMIN'" maxlength="32" /></label>
        <label><span>单位类别 *</span><input v-model="form.category" required maxlength="100" /></label>
        <label><span>资料状态</span><select v-model="form.status" :disabled="!canSetStatus"><option value="ACTIVE">已认证</option><option value="PENDING_REVIEW">待审核</option><option value="INCOMPLETE">待完善</option><option value="DISABLED">已停用</option></select></label>
        <label><span>可见范围</span><select v-model="form.visibility" :disabled="!canSetVisibility"><option value="MEMBERS">全体会员</option><option value="ASSOCIATION">本协会</option><option value="PARTNERS">本协会及友好协会</option><option value="PRIVATE">仅本企业与协会</option><option value="PUBLIC">公开</option></select></label>
        <label class="form-span-2"><span>联系地址</span><input v-model="form.address" maxlength="300" /></label>
        <label><span>联系人</span><input v-model="form.contactName" maxlength="50" /></label><label><span>联系电话</span><input v-model="form.contactPhone" maxlength="50" /></label>
        <label><span>联系邮箱</span><input v-model="form.contactEmail" type="email" maxlength="200" /></label>
        <label class="form-span-2"><span>企业简介</span><textarea v-model="form.introduction" rows="5" maxlength="2000" /></label>
      </div></section>
      <section class="form-section"><h3>能力、产品、服务与合作需求</h3><p>每行填写一项，也可以使用中文或英文逗号分隔。</p><div class="form-grid"><label><span>核心能力</span><textarea v-model="form.capabilities" rows="7" /></label><label><span>产品</span><textarea v-model="form.products" rows="7" /></label><label><span>服务</span><textarea v-model="form.services" rows="7" /></label><label><span>应用场景</span><textarea v-model="form.applicationScenarios" rows="7" /></label><label class="form-span-2"><span>合作需求</span><textarea v-model="form.cooperationNeeds" rows="6" /></label></div></section>
      <section v-if="canManageMemberConsent" class="form-section">
        <h3>企业资料跨协会授权</h3>
        <p>授权仅适用于协会字段白名单中的企业名称、类别、地址、简介、能力、产品和合作需求；联系人、电话及统一社会信用代码始终不开放。</p>
        <div v-if="!memberShareReady" class="empty-business-state"><b>当前资料不可跨协会授权</b><span>需先由协会审核为“已认证”，并把可见范围设为“本协会及友好协会”。</span></div>
        <template v-else>
          <div v-if="grantableMemberTargets.length" class="form-grid">
            <label><span>目标协会 *</span><select v-model="consentForm.targetAssociationId" @change="selectConsentTarget"><option value="" disabled>请选择</option><option v-for="target in grantableMemberTargets" :key="target.targetAssociationId" :value="target.targetAssociationId">{{ target.targetAssociationId }}</option></select></label>
            <label><span>授权截止时间 *</span><input v-model="consentForm.expiresAt" type="datetime-local" :min="minimumConsentExpiry" :max="maximumConsentExpiry || undefined" /></label>
            <div class="form-actions form-span-2"><button class="primary-button" type="button" :disabled="consentBusy || !consentForm.targetAssociationId || !consentForm.expiresAt" @click="grantMemberConsent">确认跨协会授权</button></div>
          </div>
          <div v-else class="empty-business-state"><b>没有新的可授权目标</b><span>当前有效字段策略对应的友好协会均已授权，或暂无可用策略。</span></div>
          <div v-if="memberConsentHistory.length" class="data-table-wrap"><table class="data-table">
            <thead><tr><th>目标协会</th><th>状态</th><th>授权截止</th><th>授权/撤销时间</th><th></th></tr></thead>
            <tbody><tr v-for="item in memberConsentHistory" :key="item.id">
              <td>{{ item.targetAssociationId }}</td><td><StatusBadge :value="displayBusinessStatus(isConsentActive(item) ? 'ACTIVE' : item.revokedAt ? 'REVOKED' : 'EXPIRED')" /></td><td>{{ formatDateTime(item.expiresAt) }}</td><td>{{ formatDateTime(item.revokedAt || item.createdAt) }}</td>
              <td><button v-if="isConsentActive(item)" class="text-button danger-text" type="button" :disabled="consentBusy" @click="revokeMemberConsent(item)">撤销</button></td>
            </tr></tbody>
          </table></div>
        </template>
      </section>
      <section v-if="canReview && versioned.member.status === 'PENDING_REVIEW'" class="form-section review-panel"><h3>协会审核</h3><label><span>审核意见</span><textarea v-model="reviewComment" rows="3" maxlength="1000" placeholder="可填写通过说明或需补充的内容" /></label><div class="review-actions"><button class="secondary-button" type="button" :disabled="reviewing" @click="review('INCOMPLETE')">退回补充</button><button class="primary-button" type="button" :disabled="reviewing" @click="review('ACTIVE')">审核通过</button></div></section>
      <div v-if="saveMessage" class="save-message" :class="{ conflict }" aria-live="polite">{{ saveMessage }}</div>
      <div class="form-actions"><RouterLink class="secondary-button" to="/members">取消</RouterLink><button class="primary-button" type="submit" :disabled="saving || reviewing">{{ saving ? '正在保存…' : '保存企业资料' }}</button></div>
    </form>
    </details>
  </div>
</template>
<style scoped>
.admin-master-summary{padding:18px 24px;line-height:1.8}.admin-master-summary:first-child{cursor:pointer;font-weight:700}
</style>

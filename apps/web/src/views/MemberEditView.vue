<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import PageHeader from '../components/PageHeader.vue'
import { safePageResourceError, type PageResourceError } from '../composables/useAsyncResource'
import { useAuth } from '../services/auth'
import { ApiRequestError } from '../services/http'
import { platformApi } from '../services/platform-api'
import type { MemberStatus, MemberUpsertPayload, MemberVisibility, VersionedMember } from '../types/domain'

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

const form = reactive({
  name: '', unifiedSocialCreditCode: '', category: '', address: '', contactName: '', contactPhone: '',
  introduction: '', capabilities: '', products: '', cooperationNeeds: '',
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
  form.introduction = member.introduction || ''
  form.capabilities = member.capabilities.join('\n')
  form.products = member.products.join('\n')
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
    introduction: nullable(form.introduction), capabilities: listValue(form.capabilities), products: listValue(form.products),
    cooperationNeeds: listValue(form.cooperationNeeds), visibility: form.visibility, status: form.status,
  }
}
async function load() {
  if (loading.value) return
  loading.value = true; loadError.value = null; saveMessage.value = null; conflict.value = false
  try { const result = await platformApi.member(memberId); versioned.value = result; fillForm(result) }
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
    <PageHeader eyebrow="MEMBER PROFILE" title="编辑会员企业" description="修改通过 ETag 防止并发覆盖；企业自助修改后自动进入协会审核">
      <RouterLink class="secondary-button" to="/members">返回会员列表</RouterLink>
    </PageHeader>
    <AsyncResourceState v-if="loading || loadError" :loading="loading" :error="loadError" @retry="load" />
    <form v-else-if="versioned" class="panel member-edit-form" @submit.prevent="save">
      <div class="edit-version-bar"><span>当前数据版本：{{ versioned.member.version }} · 数据范围：{{ versioned.member.visibility }}</span><button class="text-button" type="button" @click="load">重新加载最新版本</button></div>
      <section class="form-section"><h3>企业基础信息</h3><div class="form-grid">
        <label><span>企业名称 *</span><input v-model="form.name" required maxlength="200" /></label>
        <label><span>统一社会信用代码</span><input v-model="form.unifiedSocialCreditCode" maxlength="32" /></label>
        <label><span>单位类别 *</span><input v-model="form.category" required maxlength="100" /></label>
        <label><span>资料状态</span><select v-model="form.status" :disabled="!canSetStatus"><option value="ACTIVE">已认证</option><option value="PENDING_REVIEW">待审核</option><option value="INCOMPLETE">待完善</option><option value="DISABLED">已停用</option></select></label>
        <label><span>可见范围</span><select v-model="form.visibility" :disabled="!canSetVisibility"><option value="MEMBERS">全体会员</option><option value="ASSOCIATION">本协会</option><option value="PARTNERS">本协会及友好协会</option><option value="PRIVATE">仅本企业与协会</option><option value="PUBLIC">公开</option></select></label>
        <label class="form-span-2"><span>联系地址</span><input v-model="form.address" maxlength="300" /></label>
        <label><span>联系人</span><input v-model="form.contactName" maxlength="50" /></label><label><span>联系电话</span><input v-model="form.contactPhone" maxlength="50" /></label>
        <label class="form-span-2"><span>企业简介</span><textarea v-model="form.introduction" rows="5" maxlength="2000" /></label>
      </div></section>
      <section class="form-section"><h3>能力、产品与合作需求</h3><p>每行填写一项，也可以使用中文或英文逗号分隔。</p><div class="form-grid"><label><span>核心能力</span><textarea v-model="form.capabilities" rows="7" /></label><label><span>产品与服务</span><textarea v-model="form.products" rows="7" /></label><label class="form-span-2"><span>合作需求</span><textarea v-model="form.cooperationNeeds" rows="6" /></label></div></section>
      <section v-if="canReview && versioned.member.status === 'PENDING_REVIEW'" class="form-section review-panel"><h3>协会审核</h3><label><span>审核意见</span><textarea v-model="reviewComment" rows="3" maxlength="1000" placeholder="可填写通过说明或需补充的内容" /></label><div class="review-actions"><button class="secondary-button" type="button" :disabled="reviewing" @click="review('INCOMPLETE')">退回补充</button><button class="primary-button" type="button" :disabled="reviewing" @click="review('ACTIVE')">审核通过</button></div></section>
      <div v-if="saveMessage" class="save-message" :class="{ conflict }" aria-live="polite">{{ saveMessage }}</div>
      <div class="form-actions"><RouterLink class="secondary-button" to="/members">取消</RouterLink><button class="primary-button" type="submit" :disabled="saving || reviewing">{{ saving ? '正在保存…' : '保存企业资料' }}</button></div>
    </form>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import PageHeader from '../components/PageHeader.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { safePageResourceError, type PageResourceError } from '../composables/useAsyncResource'
import { useAuth } from '../services/auth'
import { platformApi } from '../services/platform-api'
import type { AssociationAccessRequest, AssociationRelationship } from '../types/domain'
import { apiActionMessage, displayBusinessStatus, formatDateTime } from './business-form'

const auth = useAuth()
const requests = ref<AssociationAccessRequest[]>([])
const relationships = ref<AssociationRelationship[]>([])
const loading = ref(false)
const busy = ref(false)
const error = ref<PageResourceError | null>(null)
const message = ref('')
const formOpen = ref(false)
const form = reactive({ targetAssociationId: '', reason: '' })
const canReview = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN'].includes(auth.user.value?.role || ''))

async function load() {
  loading.value = true; error.value = null
  try { [requests.value, relationships.value] = await Promise.all([platformApi.associationAccessRequests(), platformApi.associationRelationships()]) }
  catch (reason) { error.value = safePageResourceError(reason) }
  finally { loading.value = false }
}

async function createRequest() {
  if (busy.value) return
  busy.value = true; message.value = ''
  try {
    const saved = await platformApi.createAssociationAccessRequest(form.targetAssociationId.trim(), form.reason)
    requests.value = [saved, ...requests.value]; formOpen.value = false
    Object.assign(form, { targetAssociationId: '', reason: '' }); message.value = '接入申请已提交，等待目标协会审批。'
  } catch (reason) { message.value = apiActionMessage(reason, '接入申请提交失败，请确认目标协会 ID。') }
  finally { busy.value = false }
}

async function review(item: AssociationAccessRequest, approved: boolean) {
  if (busy.value) return
  busy.value = true; message.value = ''
  try {
    const saved = await platformApi.reviewAssociationAccessRequest(item, approved, approved ? '同意建立友好协会关系' : '暂不具备接入条件')
    requests.value = requests.value.map((value) => value.id === saved.id ? saved : value)
    message.value = approved ? '已批准申请并建立协会关系。' : '已驳回申请。'
    await load()
  } catch (reason) { message.value = apiActionMessage(reason, '审批失败。') }
  finally { busy.value = false }
}

async function change(item: AssociationRelationship, action: 'ACTIVATE' | 'SUSPEND' | 'REVOKE') {
  if (busy.value) return
  busy.value = true; message.value = ''
  try {
    const saved = await platformApi.changeAssociationRelationship(item, action, action === 'REVOKE' ? '协会管理员撤销授权' : '')
    relationships.value = relationships.value.map((value) => value.sourceAssociationId === saved.sourceAssociationId && value.targetAssociationId === saved.targetAssociationId ? saved : value)
    message.value = '协会关系状态已更新，新的数据访问范围已生效。'
  } catch (reason) { message.value = apiActionMessage(reason, '协会关系更新失败。') }
  finally { busy.value = false }
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader eyebrow="FEDERATION" title="友好协会" description="从接入申请、审批到关系暂停和撤销，对跨协会授权全程留痕">
      <button v-if="canReview" class="primary-button" @click="formOpen = true">+ 申请接入协会</button>
    </PageHeader>
    <div v-if="message" class="save-message page-message" aria-live="polite">{{ message }}</div>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <template v-else>
      <section class="panel business-section"><div class="panel-header"><div><h2>协会关系</h2><p>只有有效关系与字段共享策略同时满足时，才能跨协会查看数据</p></div><button class="text-button" @click="load">刷新</button></div>
        <div v-if="relationships.length" class="data-table-wrap"><table class="data-table"><thead><tr><th>源协会</th><th>目标协会</th><th>状态</th><th>会员数据</th><th>授权截止</th><th></th></tr></thead><tbody><tr v-for="item in relationships" :key="`${item.sourceAssociationId}-${item.targetAssociationId}`"><td>{{ item.sourceAssociationId }}</td><td>{{ item.targetAssociationId }}</td><td><StatusBadge :value="displayBusinessStatus(item.status)" /></td><td>{{ item.allowMemberData ? '允许' : '不允许' }}</td><td>{{ formatDateTime(item.expiresAt) }}</td><td><div v-if="canReview" class="inline-actions"><button v-if="item.status !== 'ACTIVE'" class="text-button" @click="change(item, 'ACTIVATE')">恢复</button><button v-if="item.status === 'ACTIVE'" class="text-button" @click="change(item, 'SUSPEND')">暂停</button><button v-if="item.status !== 'REVOKED'" class="text-button danger-text" @click="change(item, 'REVOKE')">撤销</button></div></td></tr></tbody></table></div>
        <div v-else class="empty-business-state"><b>暂无友好协会关系</b><span>批准接入申请后，关系会显示在这里。</span></div>
      </section>
      <section class="panel business-section"><div class="panel-header"><div><h2>接入申请</h2><p>审批通过时可同时开通会员数据授权</p></div></div>
        <div v-if="requests.length" class="data-table-wrap"><table class="data-table"><thead><tr><th>申请协会</th><th>目标协会</th><th>申请原因</th><th>状态</th><th>时间</th><th></th></tr></thead><tbody><tr v-for="item in requests" :key="item.id"><td>{{ item.applicantAssociationId }}</td><td>{{ item.targetAssociationId }}</td><td>{{ item.reason || '—' }}</td><td><StatusBadge :value="displayBusinessStatus(item.status)" /></td><td>{{ formatDateTime(item.requestedAt) }}</td><td><div v-if="canReview && item.status === 'PENDING'" class="inline-actions"><button class="text-button danger-text" @click="review(item, false)">驳回</button><button class="primary-button small" @click="review(item, true)">批准</button></div></td></tr></tbody></table></div>
        <div v-else class="empty-business-state"><b>暂无接入申请</b></div>
      </section>
    </template>

    <div v-if="formOpen" class="modal-backdrop" role="dialog" aria-modal="true" @click.self="formOpen = false"><form class="panel modal-card compact-modal" @submit.prevent="createRequest"><div class="modal-head"><div><span class="eyebrow">ACCESS REQUEST</span><h2>申请接入友好协会</h2></div><button type="button" class="icon-button" @click="formOpen = false">×</button></div><div class="form-grid modal-form"><label class="form-span-2"><span>目标协会 ID *</span><input v-model="form.targetAssociationId" required placeholder="UUID" /></label><label class="form-span-2"><span>申请原因</span><textarea v-model="form.reason" rows="4" maxlength="2000" /></label></div><div class="form-actions"><button type="button" class="secondary-button" @click="formOpen = false">取消</button><button class="primary-button" :disabled="busy">提交申请</button></div></form></div>
  </div>
</template>

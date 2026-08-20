<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import PageHeader from '../components/PageHeader.vue'
import { safePageResourceError, type PageResourceError } from '../composables/useAsyncResource'
import { ApiRequestError } from '../services/http'
import { platformApi } from '../services/platform-api'
import type { MemberStatus, MemberUpsertPayload, VersionedMember } from '../types/domain'

const route = useRoute()
const memberId = String(route.params.id)
const versioned = ref<VersionedMember | null>(null)
const loading = ref(false)
const loadError = ref<PageResourceError | null>(null)
const saving = ref(false)
const saveMessage = ref<string | null>(null)
const conflict = ref(false)

const form = reactive({
  name: '',
  unifiedSocialCreditCode: '',
  category: '',
  address: '',
  contactName: '',
  contactPhone: '',
  introduction: '',
  capabilities: '',
  products: '',
  cooperationNeeds: '',
  status: 'ACTIVE' as MemberStatus,
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
  form.status = member.status
}

function listValue(value: string): string[] {
  return [...new Set(value
    .split(/[\n,，、;；]+/)
    .map((item) => item.trim())
    .filter(Boolean))]
}

function nullable(value: string): string | null {
  const trimmed = value.trim()
  return trimmed || null
}

function payload(): MemberUpsertPayload {
  return {
    name: form.name.trim(),
    unifiedSocialCreditCode: nullable(form.unifiedSocialCreditCode),
    category: form.category.trim(),
    address: nullable(form.address),
    contactName: nullable(form.contactName),
    contactPhone: nullable(form.contactPhone),
    introduction: nullable(form.introduction),
    capabilities: listValue(form.capabilities),
    products: listValue(form.products),
    cooperationNeeds: listValue(form.cooperationNeeds),
    status: form.status,
  }
}

async function load() {
  if (loading.value) return
  loading.value = true
  loadError.value = null
  saveMessage.value = null
  conflict.value = false
  try {
    const result = await platformApi.member(memberId)
    versioned.value = result
    fillForm(result)
  } catch (reason) {
    loadError.value = safePageResourceError(reason)
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!versioned.value || saving.value) return
  saving.value = true
  saveMessage.value = null
  conflict.value = false
  try {
    const result = await platformApi.updateMember(memberId, payload(), versioned.value.etag)
    versioned.value = result
    fillForm(result)
    saveMessage.value = '企业资料已保存。'
  } catch (reason) {
    if (reason instanceof ApiRequestError && reason.status === 412) {
      conflict.value = true
      saveMessage.value = '保存失败：资料已被其他用户更新，请重新加载最新版本后再修改。'
    } else if (reason instanceof ApiRequestError && reason.status === 403) {
      saveMessage.value = '保存失败：当前账号没有企业资料编辑权限。'
    } else {
      saveMessage.value = '保存失败，请稍后重试。'
    }
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader eyebrow="MEMBER PROFILE" title="编辑会员企业" description="保存时使用服务器返回的 ETag，防止覆盖他人的并发修改">
      <RouterLink class="secondary-button" to="/members">返回会员列表</RouterLink>
    </PageHeader>

    <AsyncResourceState
      v-if="loading || loadError"
      :loading="loading"
      :error="loadError"
      @retry="load"
    />

    <form v-else-if="versioned" class="panel member-edit-form" @submit.prevent="save">
      <div class="edit-version-bar">
        <span>当前数据版本：{{ versioned.member.version }}</span>
        <button class="text-button" type="button" @click="load">重新加载最新版本</button>
      </div>

      <section class="form-section">
        <h3>企业基础信息</h3>
        <div class="form-grid">
          <label><span>企业名称 *</span><input v-model="form.name" required maxlength="200" /></label>
          <label><span>统一社会信用代码</span><input v-model="form.unifiedSocialCreditCode" maxlength="32" /></label>
          <label><span>单位类别 *</span><input v-model="form.category" required maxlength="100" /></label>
          <label><span>资料状态</span>
            <select v-model="form.status">
              <option value="ACTIVE">已认证</option>
              <option value="PENDING_REVIEW">待审核</option>
              <option value="INCOMPLETE">待完善</option>
              <option value="DISABLED">已停用</option>
            </select>
          </label>
          <label class="form-span-2"><span>联系地址</span><input v-model="form.address" maxlength="300" /></label>
          <label><span>联系人</span><input v-model="form.contactName" maxlength="50" /></label>
          <label><span>联系电话</span><input v-model="form.contactPhone" maxlength="50" /></label>
          <label class="form-span-2"><span>企业简介</span><textarea v-model="form.introduction" rows="5" maxlength="2000" /></label>
        </div>
      </section>

      <section class="form-section">
        <h3>能力、产品与合作需求</h3>
        <p>每行填写一项，也可以使用中文或英文逗号分隔。</p>
        <div class="form-grid">
          <label><span>核心能力</span><textarea v-model="form.capabilities" rows="7" /></label>
          <label><span>产品与服务</span><textarea v-model="form.products" rows="7" /></label>
          <label class="form-span-2"><span>合作需求</span><textarea v-model="form.cooperationNeeds" rows="6" /></label>
        </div>
      </section>

      <div v-if="saveMessage" class="save-message" :class="{ conflict }" aria-live="polite">
        {{ saveMessage }}
      </div>
      <div class="form-actions">
        <RouterLink class="secondary-button" to="/members">取消</RouterLink>
        <button class="primary-button" type="submit" :disabled="saving">
          {{ saving ? '正在保存…' : '保存企业资料' }}
        </button>
      </div>
    </form>
  </div>
</template>

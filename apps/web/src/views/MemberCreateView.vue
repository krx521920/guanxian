<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import { useAuth } from '../services/auth'
import { ApiRequestError } from '../services/http'
import { platformApi } from '../services/platform-api'
import type { MemberStatus, MemberUpsertPayload, MemberVisibility } from '../types/domain'

const router = useRouter()
const auth = useAuth()
const saving = ref(false)
const message = ref<string | null>(null)
const canSetActive = computed(() => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN'].includes(auth.user.value?.role || ''))
const form = reactive({
  name: '', unifiedSocialCreditCode: '', category: '', address: '', contactName: '', contactPhone: '',
  introduction: '', capabilities: '', products: '', cooperationNeeds: '',
  visibility: 'MEMBERS' as MemberVisibility,
  status: 'PENDING_REVIEW' as MemberStatus,
})

function listValue(value: string): string[] {
  return [...new Set(value.split(/[\n,，、;；]+/).map((item) => item.trim()).filter(Boolean))]
}
function nullable(value: string): string | null { return value.trim() || null }
function payload(): MemberUpsertPayload {
  return {
    name: form.name.trim(), unifiedSocialCreditCode: nullable(form.unifiedSocialCreditCode),
    category: form.category.trim(), address: nullable(form.address), contactName: nullable(form.contactName),
    contactPhone: nullable(form.contactPhone), introduction: nullable(form.introduction),
    capabilities: listValue(form.capabilities), products: listValue(form.products),
    cooperationNeeds: listValue(form.cooperationNeeds), visibility: form.visibility,
    status: canSetActive.value ? form.status : 'PENDING_REVIEW',
  }
}
async function submit() {
  if (saving.value) return
  saving.value = true
  message.value = null
  try {
    const result = await platformApi.createMember(payload())
    await router.push(`/members/${result.member.id}/edit`)
  } catch (reason) {
    message.value = reason instanceof ApiRequestError && reason.status === 409
      ? '新增失败：企业名称或统一社会信用代码已存在。'
      : '新增失败，请核对填写内容后重试。'
  } finally { saving.value = false }
}
</script>

<template>
  <div>
    <PageHeader title="新增会员企业" description="新增资料将纳入协会数据域，并按账号权限进入审核流程">
      <RouterLink class="secondary-button" to="/members">返回会员列表</RouterLink>
    </PageHeader>
    <form class="panel member-edit-form" @submit.prevent="submit">
      <section class="form-section">
        <h3>企业基础信息</h3>
        <div class="form-grid">
          <label><span>企业名称 *</span><input v-model="form.name" required maxlength="200" /></label>
          <label><span>统一社会信用代码</span><input v-model="form.unifiedSocialCreditCode" maxlength="32" /></label>
          <label><span>单位类别 *</span><input v-model="form.category" required maxlength="100" /></label>
          <label><span>可见范围</span><select v-model="form.visibility"><option value="MEMBERS">全体会员</option><option value="ASSOCIATION">本协会</option><option value="PARTNERS">本协会及友好协会</option><option value="PRIVATE">仅本企业与协会</option><option value="PUBLIC">公开</option></select></label>
          <label v-if="canSetActive"><span>初始状态</span><select v-model="form.status"><option value="PENDING_REVIEW">待审核</option><option value="ACTIVE">已认证</option><option value="INCOMPLETE">待完善</option></select></label>
          <label class="form-span-2"><span>联系地址</span><input v-model="form.address" maxlength="300" /></label>
          <label><span>联系人</span><input v-model="form.contactName" maxlength="50" /></label>
          <label><span>联系电话</span><input v-model="form.contactPhone" maxlength="50" /></label>
          <label class="form-span-2"><span>企业简介</span><textarea v-model="form.introduction" rows="5" maxlength="2000" /></label>
        </div>
      </section>
      <section class="form-section">
        <h3>能力、产品与合作需求</h3><p>每行填写一项，也可以使用逗号或分号分隔。</p>
        <div class="form-grid">
          <label><span>核心能力</span><textarea v-model="form.capabilities" rows="6" /></label>
          <label><span>产品与服务</span><textarea v-model="form.products" rows="6" /></label>
          <label class="form-span-2"><span>合作需求</span><textarea v-model="form.cooperationNeeds" rows="5" /></label>
        </div>
      </section>
      <div v-if="message" class="save-message conflict" aria-live="polite">{{ message }}</div>
      <div class="form-actions"><RouterLink class="secondary-button" to="/members">取消</RouterLink><button class="primary-button" type="submit" :disabled="saving">{{ saving ? '正在保存…' : '创建企业资料' }}</button></div>
    </form>
  </div>
</template>

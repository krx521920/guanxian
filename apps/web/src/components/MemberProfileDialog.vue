<script setup lang="ts">
import type { MemberProfile } from '../types/domain'
import BusinessResultDialog from './BusinessResultDialog.vue'
import { memberStatus } from '../services/assistant-business-results'
import { formatDateTime } from '../views/business-form'
defineProps<{ member: MemberProfile; returnFocus?: HTMLElement | null }>()
const emit = defineEmits<{ close: [] }>()
const visibility = (value: string) => ({ PRIVATE: '私有', ASSOCIATION: '本协会', MEMBERS: '会员可见', PARTNERS: '合作协会', PUBLIC: '公开' }[value] || value)
</script>
<template>
  <BusinessResultDialog :title="`${member.name} 企业详情`" :return-focus="returnFocus" @close="emit('close')">
    <p class="source-note">当前授权接口返回的最新档案 · 查询时重新核验权限</p>
    <dl class="profile-fields">
      <div v-for="(value, label) in { 单位类别: member.category, 统一信用代码: member.unifiedSocialCreditCode, 联系人: member.contactName, 联系电话: member.contactPhone, 联系邮箱: member.contactEmail, 审核状态: memberStatus(member.status), 可见范围: visibility(member.visibility), 档案版本: `v${member.version}`, 更新时间: formatDateTime(member.updatedAt) }" :key="label"><dt>{{ label }}</dt><dd>{{ value || '未提供' }}</dd></div>
    </dl>
    <h3>企业简介</h3><p class="profile-copy">{{ member.introduction || '未提供' }}</p>
    <section v-for="(values, label) in { 核心能力: member.capabilities, 产品: member.products, 服务: member.services, 应用场景: member.applicationScenarios, 合作需求: member.cooperationNeeds }" :key="label"><h3>{{ label }}</h3><p class="profile-copy">{{ values?.join('、') || '未提供' }}</p></section>
  </BusinessResultDialog>
</template>
<style scoped>
.source-note{font-size:13px;color:#596d80}.profile-fields{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:18px}.profile-fields div{min-width:0}dt{font-size:13px;color:#63768a}dd{margin:4px 0 0;overflow-wrap:anywhere}.profile-copy{white-space:pre-wrap;overflow-wrap:anywhere}h3{font-size:16px;margin-top:24px}@media(max-width:600px){.profile-fields{grid-template-columns:repeat(2,minmax(0,1fr))}}
</style>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import ProfileWorkflowPanel from '../components/ProfileWorkflowPanel.vue'
import { myEnterpriseApi, type MyEnterprise } from '../services/enterprise-onboarding'
import type { MemberProfile } from '../types/domain'

const data = ref<MyEnterprise | null>(null), form = ref<MemberProfile | null>(null)
const busy = ref(false), error = ref('')
const lists = [ ['capabilities','技术能力',100], ['products','产品',100], ['services','服务',100], ['applicationScenarios','应用场景',100], ['cooperationNeeds','合作需求',200] ] as const
const statusLabels: Record<string,string> = { ACTIVE:'已审核', PENDING_REVIEW:'待协会审核', INCOMPLETE:'待完善', DISABLED:'已停用' }
function apply(value: MyEnterprise) {
  data.value = value; form.value = JSON.parse(JSON.stringify(value.profile))
}
async function load() {
  busy.value = true; error.value = ''
  try { apply(await myEnterpriseApi.get()) }
  catch (e) { error.value = e instanceof Error ? e.message : '无法读取本企业资料'; data.value = null; form.value = null }
  finally { busy.value = false }
}
function setList(key: typeof lists[number][0], text: string) {
  if (form.value) form.value[key] = text.split('\n')
}
onMounted(load)
</script>
<template>
  <div class="my-enterprise-page">
    <PageHeader title="我的企业" description="维护已绑定企业的资料，提交后由协会审核。"><RouterLink class="secondary-button" to="/enterprise">返回企业工作台</RouterLink></PageHeader>
    <p v-if="error" role="alert" class="own-error">{{ error }}</p>
    <p v-if="busy" role="status">正在处理…</p>
    <button v-if="!data?.canEdit" class="text-button" :disabled="busy" @click="load">重新加载资料</button>
    <ProfileWorkflowPanel v-if="data?.canEdit" :enterprise-id="data.profile.id" />
    <form v-if="form && data && !data.canEdit" class="panel own-form" @submit.prevent>
      <div class="own-heading"><div><h2>{{ data.profile.name }}</h2><p>{{ statusLabels[data.profile.status] || data.profile.status }} · 资料版本 {{ data.profile.version }}</p></div><span class="tag">{{ data.canEdit ? '本企业负责人' : '本企业只读成员' }}</span></div>
      <p class="own-note">当前显示内部已生效资料，不包含负责人尚未通过审核的草稿。主体身份变更请联系协会核验。</p>
      <fieldset :disabled="busy || !data.canEdit">
        <legend>企业基本资料</legend>
        <div class="own-grid">
          <label>企业名称<input :value="data.profile.name" disabled /></label>
          <label v-if="data.canEdit">统一社会信用代码<input :value="data.profile.unifiedSocialCreditCode || '尚未登记'" disabled /></label>
          <label>业务类别 *<input v-model="form.category" required maxlength="100" /></label>
          <label v-if="data.canEdit">联系地址<input v-model="form.address" maxlength="300" /></label>
          <label v-if="data.canEdit">联系人<input v-model="form.contactName" maxlength="50" /></label>
          <label v-if="data.canEdit">联系电话<input v-model="form.contactPhone" maxlength="50" /></label>
          <label v-if="data.canEdit">联系邮箱<input v-model="form.contactEmail" type="email" maxlength="254" /></label>
        </div>
        <label>企业简介<textarea v-model="form.introduction" rows="5" maxlength="2000" /></label>
        <div class="own-grid"><label v-for="[key,label,maxLength] in lists" :key="key">{{ label }}<small>每行一项，最多 50 项，每项 {{ maxLength }} 字</small><textarea :value="(form[key] || []).join('\n')" rows="4" @input="setList(key, ($event.target as HTMLTextAreaElement).value)" /></label></div>
      </fieldset>
      <p v-if="!data.canEdit" class="own-note">当前身份仅可查看，联系方式等管理字段不在本页面展示。如需维护资料，请由企业负责人操作。</p>
    </form>
  </div>
</template>
<style scoped>
.own-form{padding:28px;margin-top:18px}.own-heading{display:flex;justify-content:space-between;gap:20px;align-items:start}.own-heading h2{margin:0 0 10px;font-size:23px}.own-heading p{color:var(--muted)}.own-form fieldset{padding:0;border:0;min-width:0}.own-form legend{font-weight:700;font-size:18px;padding:18px 0}.own-form label{display:grid;gap:8px;margin-bottom:18px;font-size:14px}.own-form small{color:var(--muted)}.own-form input,.own-form textarea{font:inherit;width:100%;border:1px solid var(--line);border-radius:8px;padding:12px;background:var(--panel);color:var(--ink)}.own-form input:disabled,.own-form fieldset:disabled textarea{background:#f2f5f5;color:#536768;opacity:1}.own-grid{display:grid;grid-template-columns:1fr 1fr;gap:0 22px}.own-note{background:#edf6f4;padding:16px;border-radius:10px;line-height:1.8;color:#3f605e}.own-error{background:#fff1ee;color:#943d2c;padding:16px;border-radius:10px;line-height:1.8}.own-actions{display:flex;flex-wrap:wrap;align-items:center;gap:18px;margin-top:20px}.own-actions span{color:var(--muted);font-size:13px}.own-form button:disabled{opacity:.5;cursor:not-allowed}@media(max-width:680px){.own-grid{grid-template-columns:1fr}.own-form{padding:18px}.own-heading{flex-direction:column}}
</style>

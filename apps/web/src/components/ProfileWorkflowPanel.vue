<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { profileApi, profileDiff, publicPreview, displayField, type ProfileWorkflow } from '../services/profile-workflow'
import type { MemberUpsertPayload } from '../types/domain'
const props = defineProps<{ enterpriseId: string }>()
const emit = defineEmits<{ approved: [] }>()
const data = ref<ProfileWorkflow | null>(null), form = ref<MemberUpsertPayload | null>(null)
const baseline = ref(''), busy = ref(false), error = ref(''), message = ref(''), note = ref(''), consent = ref(false)
const labels = { DRAFT: '草稿 · 尚未提交', SUBMITTED: '待审核 · 版本已冻结', APPROVED: '审核通过 · 内部已生效', REJECTED: '已退回 · 请修改后重提' }
const dirty = computed(() => !!form.value && JSON.stringify(form.value) !== baseline.value)
const diffs = computed(() => data.value?.draft ? profileDiff(data.value.official, data.value.draft.content) : [])
const preview = computed(() => data.value?.approved ? publicPreview(data.value.approved.profile) : null)
const lists = [['capabilities','技术能力',100],['products','产品',100],['services','服务',100],['applicationScenarios','应用场景',100],['cooperationNeeds','合作需求（内部）',200]] as const
const editable = computed(() => data.value?.canEdit && !busy.value)
function apply(value: ProfileWorkflow) {
  data.value = value
  form.value = JSON.parse(JSON.stringify(value.draft?.content || value.official))
  baseline.value = JSON.stringify(form.value); consent.value = false
}
async function load() {
  if (dirty.value && !window.confirm('重新加载将放弃未保存的修改，是否继续？')) return
  busy.value = true; error.value = ''
  try { apply(await profileApi.get(props.enterpriseId)) }
  catch (e) { data.value = null; error.value = e instanceof Error ? e.message : '资料流程读取失败' }
  finally { busy.value = false }
}
function setList(key: typeof lists[number][0], value: string) { if (form.value) form.value[key] = value.split('\n') }
async function run(action: 'save' | 'submit' | 'approve' | 'reject' | 'consent' | 'publish' | 'withdraw') {
  if (!data.value || !form.value || busy.value) return
  if (action !== 'save' && action !== 'withdraw' && dirty.value) { error.value = '请先保存草稿，操作只针对服务器已保存的版本'; return }
  if (['approve','reject','withdraw'].includes(action) && !note.value.trim()) { error.value = '请填写审核说明或退回／下架原因'; return }
  if (action === 'consent' && !consent.value) return
  if (action === 'publish' && !window.confirm('确认已人工检查下方公开预览，将这个已审核且获授权的版本发布给所有游客？')) return
  busy.value = true; error.value = ''; message.value = ''
  try {
    let result: ProfileWorkflow
    if (action === 'save') {
      const fields = { ...form.value }
      for (const [key,label,maxLength] of lists) {
        const values = [...new Set((fields[key] || []).map(value => value.trim()).filter(Boolean))]
        if (values.length > 50 || values.some(value => value.length > maxLength)) throw new Error(`${label}最多 50 项，每项最多 ${maxLength} 字`)
        fields[key] = values
      }
      result = await profileApi.save(props.enterpriseId, data.value.version, data.value.official.version, fields)
    } else {
      const endpoint = action === 'approve' || action === 'reject' ? 'review' : action
      const body = endpoint === 'review' ? { approve: action === 'approve', note: note.value }
        : endpoint === 'withdraw' ? { note: note.value } : endpoint === 'consent' ? { confirmed: true } : undefined
      result = await profileApi.action(props.enterpriseId, data.value.version, endpoint, body)
    }
    const local = form.value, previousBaseline = baseline.value, preserveLocal = action === 'withdraw' && dirty.value
    apply(result)
    if (preserveLocal) { form.value = local; baseline.value = previousBaseline }
    note.value = ''
    message.value = ({ save:'草稿已保存。正式资料与公开版本均未改变。', submit:'已提交审核。审核中的内容不会提前生效或公开。',
      approve:'审核通过，内部资料已更新；游客仍只看到原公开版本。', reject:'已退回，负责人可以看到原因并重新修改。',
      consent:'已记录这个审核版本的公开授权，等待管理员发布。', publish:'已发布下方审核版本。', withdraw:'已撤回公开展示，原公开授权同时失效。' })[action]
    if (action === 'approve') emit('approved')
  } catch (e) {
    error.value = e instanceof Error && 'status' in e && e.status === 412
      ? '版本已变化，未覆盖他人的修改。您的输入仍保留，请先复制留存，再重新加载核对。'
      : e instanceof Error ? e.message : '操作失败'
  } finally { busy.value = false }
}
onBeforeRouteLeave(() => !dirty.value || window.confirm('有未保存的资料草稿，确定离开吗？'))
onMounted(load)
</script>
<template>
  <section class="profile-workflow" aria-label="资料草稿与审核发布">
    <header class="workflow-heading"><div><h2>资料草稿与审核发布</h2><p>保存草稿 → 提交审核 → 内部生效 → 企业授权 → 公开发布</p></div><button type="button" class="secondary-button" :disabled="busy" @click="load">重新加载草稿</button></header>
    <p v-if="error" role="alert" class="workflow-error">{{ error }}</p><p v-if="message" role="status" class="workflow-note">{{ message }}</p>
    <p v-if="busy" role="status">正在处理…</p>
    <template v-if="data && form">
      <div class="workflow-summary"><span>内部资料 v{{ data.official.version }} · {{ data.official.status === 'ACTIVE' ? '可用' : data.official.status }}</span><strong>{{ data.draft ? labels[data.draft.status] : '尚无草稿' }}</strong><span>{{ data.published ? '游客看到已发布快照' : '尚未公开' }}</span></div>
      <p class="workflow-note">草稿不会覆盖正式资料。名称、信用代码及协会归属需单独核验；联系方式、详细地址和合作需求不进入游客公开版本。</p>
      <aside v-if="data.draft?.reviewNote" class="review-feedback" aria-label="审核反馈"><strong>{{ data.draft.status === 'APPROVED' ? '审核说明' : '上次审核／退回原因' }}</strong><p>{{ data.draft.reviewNote }}</p></aside>
      <form @submit.prevent="run('save')">
        <fieldset :disabled="!editable">
          <legend>{{ data.official.name }} · 资料内容</legend>
          <div class="workflow-grid">
            <label>业务类别 *<input v-model="form.category" required maxlength="100" /></label>
            <label>联系地址（内部）<input v-model="form.address" maxlength="300" /></label>
            <label>联系人（内部）<input v-model="form.contactName" maxlength="50" /></label>
            <label>联系电话（内部）<input v-model="form.contactPhone" maxlength="50" /></label>
            <label>联系邮箱（内部）<input v-model="form.contactEmail" type="email" maxlength="254" /></label>
          </div>
          <label>企业简介<textarea v-model="form.introduction" rows="5" maxlength="2000" /></label>
          <div class="workflow-grid"><label v-for="[key,label,maxLength] in lists" :key="key">{{ label }}<small>每行一项，每项最多 {{ maxLength }} 字，最多 50 项</small><textarea :value="(form[key] || []).join('\n')" rows="3" @input="setList(key, ($event.target as HTMLTextAreaElement).value)" /></label></div>
        </fieldset>
        <div class="workflow-actions"><button v-if="data.canEdit" class="primary-button" :disabled="busy || (!dirty && !!data.draft)">保存草稿</button><button v-if="data.draft?.status === 'DRAFT'" type="button" class="secondary-button" :disabled="busy || dirty" @click="run('submit')">提交协会审核</button><small>{{ dirty ? '有未保存的修改' : '已与服务器版本同步' }}</small></div>
      </form>
      <details v-if="data.draft" :open="data.draft.status === 'SUBMITTED'" class="workflow-diff"><summary>核对正式资料与本次草稿差异（{{ diffs.length }} 项）</summary><div class="workflow-table"><table><thead><tr><th>字段</th><th>当前正式资料</th><th>已保存草稿</th></tr></thead><tbody><tr v-for="change in diffs" :key="change.key"><th>{{ change.label }}</th><td>{{ change.before }}</td><td>{{ change.after }}</td></tr></tbody></table></div><p v-if="!diffs.length">与当前正式资料一致。</p></details>
      <section v-if="data.canReview" class="workflow-review"><h3>独立审核</h3><p>核对上方冻结草稿与差异。审核通过仅更新内部资料；退回原因将展示给负责人。</p><label>审核说明／退回原因 *<textarea v-model="note" maxlength="1000" rows="3" /></label><div class="workflow-actions"><button type="button" class="secondary-button" :disabled="busy || dirty || !note.trim()" @click="run('reject')">退回修改</button><button type="button" class="primary-button" :disabled="busy || dirty || !note.trim()" @click="run('approve')">审核通过并内部生效</button></div></section>
      <section v-if="data.approved && preview" class="workflow-public"><h3>游客视角预览 · 已审核版本</h3><p>以下为可公开的全部业务字段。请检查自由文本是否夹带私人号码、合同或敏感信息；不公开内部地址与联系字段。</p><dl><template v-for="(value,key) in preview" :key="key"><dt>{{ ({name:'企业名称',category:'业务类别',introduction:'企业简介',capabilities:'技术能力',products:'产品',services:'服务',applicationScenarios:'应用场景'} as Record<string,string>)[key] }}</dt><dd>{{ displayField(value) }}</dd></template></dl>
        <p>授权状态：{{ data.approved.consentedAt ? '已记录企业授权（发布前再次校验版本与状态）' : '尚未授权' }}。公开发布是独立操作。</p>
        <label v-if="data.canConsent" class="workflow-checkbox"><input v-model="consent" type="checkbox" />我代表本企业确认上述审核版本允许向所有游客公开，并已检查自由文本。</label>
        <div class="workflow-actions"><button v-if="data.canConsent" type="button" class="secondary-button" :disabled="busy || dirty || !consent" @click="run('consent')">确认本版本公开授权</button><button v-if="data.canPublish" type="button" class="primary-button" :disabled="busy || dirty" @click="run('publish')">发布已审核版本</button></div>
        <template v-if="data.canWithdraw && data.published"><label>撤回／下架原因 *<textarea v-model="note" rows="2" maxlength="1000" /></label><button type="button" class="secondary-button" :disabled="busy || !note.trim()" @click="run('withdraw')">立即撤回公开展示</button></template>
      </section>
    </template>
  </section>
</template>
<style scoped>
.profile-workflow{background:var(--panel);border:1px solid var(--line);border-radius:16px;padding:26px;margin:20px 0;min-width:0}.workflow-heading{display:flex;justify-content:space-between;gap:16px;align-items:start}.workflow-heading h2{margin:0 0 8px}.workflow-heading p,.profile-workflow small{color:var(--muted);line-height:1.8}.workflow-summary{display:flex;flex-wrap:wrap;gap:18px;padding:16px 0}.workflow-note,.review-feedback{padding:16px;background:#edf6f4;border-radius:8px;line-height:1.8}.review-feedback{background:#fff4de}.review-feedback p{white-space:pre-wrap;margin-bottom:0}.workflow-error{background:#fff0ed;color:#963d2b;padding:16px;border-radius:8px}.profile-workflow fieldset{border:0;padding:0;min-width:0;margin-top:20px}.profile-workflow legend{font-weight:700;margin-bottom:20px}.workflow-grid{display:grid;grid-template-columns:1fr 1fr;gap:0 20px}.profile-workflow label{display:grid;gap:8px;margin-bottom:16px}.profile-workflow input,.profile-workflow textarea{font:inherit;padding:12px;border:1px solid var(--line);border-radius:8px;max-width:100%;width:100%;box-sizing:border-box;color:var(--ink);background:var(--panel)}.profile-workflow fieldset:disabled{opacity:.8}.profile-workflow button:disabled{opacity:.5;cursor:not-allowed}.workflow-actions{display:flex;flex-wrap:wrap;gap:12px;align-items:center;margin:16px 0}.workflow-diff,.workflow-review,.workflow-public{border-top:1px solid var(--line);padding-top:20px;margin-top:24px}.workflow-diff summary{cursor:pointer;font-weight:700}.workflow-table{overflow-x:auto;margin-top:16px}.workflow-table table{border-collapse:collapse;width:100%;font-size:14px}.workflow-table td,.workflow-table th{text-align:left;vertical-align:top;border-bottom:1px solid var(--line);padding:12px;white-space:pre-wrap;overflow-wrap:anywhere}.workflow-public dl{display:grid;grid-template-columns:120px 1fr;background:#f5f8fa;padding:18px;gap:14px}.workflow-public dd{margin:0;white-space:pre-wrap;overflow-wrap:anywhere}.workflow-public p,.workflow-review p{line-height:1.8}.profile-workflow .workflow-checkbox{display:flex;align-items:start;line-height:1.7}.workflow-checkbox input{width:auto;margin-top:6px;flex-shrink:0}@media(max-width:680px){.profile-workflow{padding:16px}.workflow-grid{grid-template-columns:1fr}.workflow-heading{flex-direction:column}.workflow-public dl{grid-template-columns:1fr;gap:8px}.workflow-public dd{margin-bottom:12px}}
</style>

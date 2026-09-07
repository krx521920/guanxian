<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useAuth } from '../services/auth'
import { modelSettingsError, personalModelApi, type ModelSettings, type ModelTestResult } from '../services/personal-model'

const emit = defineEmits<{ close: []; changed: [settings: ModelSettings] }>()
const auth = useAuth()
const settings = ref<ModelSettings | null>(null)
const provider = ref('')
const model = ref('')
const apiKey = ref('')
const busy = ref(false)
const error = ref('')
const notice = ref('')
const confirmDelete = ref(false)
const testResult = ref<ModelTestResult | null>(null)
const dialog = ref<HTMLElement | null>(null)
let revision = 0
let controller: AbortController | null = null
const selectedProvider = computed(() => settings.value?.providers.find(item => item.id === provider.value))
const retainedKey = computed(() => settings.value?.saved?.hasKey && settings.value.saved.provider === provider.value)
const dirty = computed(() => !settings.value?.saved || apiKey.value !== ''
  || provider.value !== settings.value.saved.provider || model.value.trim() !== settings.value.saved.model
  || !settings.value.saved.enabled)
const canSave = computed(() => settings.value?.storageAvailable && !busy.value && !!provider.value
  && /^[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}$/.test(model.value.trim())
  && (!!apiKey.value.trim() || retainedKey.value))
const setupWarning = computed(() => {
  if (!settings.value) return ''
  if (!settings.value.storageAvailable && !settings.value.egressAllowed) return '模型服务尚未初始化：需要配置密钥加密和模型网络访问。请联系管理员完成一次性配置。'
  if (!settings.value.storageAvailable) return '密钥加密服务未就绪，暂不能保存 API Key。请联系管理员完成配置。'
  if (!settings.value.egressAllowed) return '模型网络访问未开启：可以保存配置，开启后才能调用大模型。'
  return ''
})

function apply(value: ModelSettings) {
  settings.value = value
  provider.value = value.saved?.provider || value.providers[0]?.id || ''
  model.value = value.saved?.model || ''
  apiKey.value = ''
}

async function run(action: (signal: AbortSignal) => Promise<void>) {
  const current = ++revision
  const next = new AbortController()
  controller?.abort()
  controller = next
  busy.value = true
  error.value = ''
  try { await action(next.signal) }
  catch (reason) {
    if (current === revision && !next.signal.aborted) error.value = modelSettingsError(reason)
  } finally {
    if (current === revision) busy.value = false
  }
}

function load() {
  void run(async signal => {
    const value = await personalModelApi.get(signal)
    if (!signal.aborted) apply(value)
  })
}

function changeProvider() {
  apiKey.value = ''
  model.value = ''
  notice.value = ''
  testResult.value = null
}

async function save() {
  if (!canSave.value) return
  testResult.value = null
  notice.value = ''
  await run(async signal => {
    const value = await personalModelApi.save({ provider: provider.value, model: model.value.trim(),
      apiKey: apiKey.value, enabled: true, externalDataConsent: true }, signal)
    if (signal.aborted) return
    apply(value)
    notice.value = value.egressAllowed
      ? '已保存并启用，下次提问将调用此模型，并开始新的会话上下文。'
      : '配置已保存，模型网络访问尚未开启，当前仍使用本地模式。'
    emit('changed', value)
  })
  apiKey.value = ''
}

async function testConnection() {
  if (busy.value || dirty.value || !settings.value?.storageAvailable || !settings.value.egressAllowed) return
  testResult.value = null
  await run(async signal => {
    const value = await personalModelApi.test(true, signal)
    if (!signal.aborted) testResult.value = value
  })
}

async function remove() {
  if (!confirmDelete.value || busy.value) return
  await run(async signal => {
    await personalModelApi.remove(signal)
    if (signal.aborted || !settings.value) return
    const value = { ...settings.value, saved: null }
    apply(value)
    confirmDelete.value = false
    testResult.value = null
    notice.value = '个人配置与密钥已删除，恢复平台默认模式。'
    emit('changed', value)
  })
}

function close() {
  if (busy.value) return
  apiKey.value = ''
  emit('close')
}

function handleKey(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.stopPropagation(); close() }
  if (event.key !== 'Tab' || !dialog.value) return
  const targets = [...dialog.value.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), select:not(:disabled), a[href]')]
  const first = targets[0]
  const last = targets[targets.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}

watch(() => auth.user.value?.id, () => {
  controller?.abort()
  revision += 1
  apiKey.value = ''
  settings.value = null
  emit('close')
})
onMounted(() => { load(); void nextTick(() => dialog.value?.focus()) })
onBeforeUnmount(() => { revision += 1; controller?.abort(); apiKey.value = '' })
</script>

<template>
  <Teleport to="body">
    <div class="model-settings-backdrop" @click.self="close">
      <section ref="dialog" class="model-settings-panel" role="dialog" aria-modal="true" aria-labelledby="model-settings-title" tabindex="-1" @keydown="handleKey">
        <header class="model-settings-header">
          <div><span class="model-settings-eyebrow">个人专属 · 仅对当前账号生效</span><h2 id="model-settings-title">模型接入</h2></div>
          <button type="button" class="model-close" aria-label="关闭模型接入" :disabled="busy" @click="close">×</button>
        </header>
        <div class="model-settings-body">
          <p class="model-intro">填写 API 后即可调用大模型，仅对当前账号生效。</p>
          <p v-if="busy && !settings" role="status">正在读取个人配置…</p>
          <template v-if="settings">
            <p v-if="setupWarning" class="model-warning" role="status">{{ setupWarning }}</p>
            <form id="personal-model-form" class="model-form" @submit.prevent="save">
              <label for="personal-model-provider">模型厂商</label>
              <select id="personal-model-provider" v-model="provider" :disabled="busy" @change="changeProvider">
                <option v-for="item in settings.providers" :key="item.id" :value="item.id">{{ item.label }}</option>
              </select>
              <div class="model-endpoint"><span>官方接口</span><code>{{ selectedProvider?.endpoint }}</code><a v-if="selectedProvider" :href="selectedProvider.documentationUrl" target="_blank" rel="noopener noreferrer">获取 API Key / 查看文档 ↗</a></div>
              <label for="personal-model-id">模型 ID</label>
              <input id="personal-model-id" v-model="model" maxlength="160" autocomplete="off" :disabled="busy" :placeholder="selectedProvider?.modelHint" />
              <label for="personal-model-key">API Key <span v-if="retainedKey" class="model-key-saved">已安全保存</span></label>
              <input id="personal-model-key" v-model="apiKey" type="password" maxlength="4096" autocomplete="new-password" spellcheck="false" :disabled="busy || !settings.storageAvailable" :placeholder="retainedKey ? '留空保留原密钥；输入新密钥可替换' : '粘贴此厂商的 API Key'" />
              <small>密钥加密保存、不回显；网页会员订阅不等于 API 余额。</small>
              <p class="model-usage-note">保存即启用并同意将提问、会话上下文及可见业务资料发送给所选厂商，费用计入您的 API 账号。连接测试仅发送测试文本。</p>
            </form>
            <p v-if="dirty && settings.saved" class="model-hint">有未保存的修改，请先保存再测试连接。</p>
          </template>
          <p v-if="error" class="model-warning" role="alert">{{ error }}</p>
          <button v-if="!settings && !busy" class="model-secondary" type="button" @click="load">重新加载</button>
          <p v-if="notice" class="model-success" role="status">{{ notice }}</p>
          <p v-if="testResult" :class="testResult.success ? 'model-success' : 'model-warning'" role="status">{{ testResult.message }}<span v-if="testResult.success"> 测试首个文本响应：{{ testResult.latencyMs }} ms（不代表业务问答耗时）。</span></p>
          <div v-if="confirmDelete" class="model-delete-confirm" role="alert"><span>删除当前账号的配置与密钥？之后将使用平台默认模式。</span><button type="button" :disabled="busy" @click="remove">确认删除</button><button type="button" :disabled="busy" @click="confirmDelete = false">取消</button></div>
        </div>
        <footer class="model-settings-actions">
          <button type="button" class="model-delete" :disabled="busy || !settings?.saved" @click="confirmDelete = true">删除配置</button>
          <button type="button" class="model-secondary" :disabled="busy || dirty || !settings?.egressAllowed || !settings?.storageAvailable" @click="testConnection">测试连接</button>
          <button type="submit" form="personal-model-form" class="model-primary" :disabled="!canSave">{{ busy ? '处理中…' : settings?.egressAllowed ? '保存并启用' : '保存配置' }}</button>
        </footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.model-settings-backdrop { position: fixed; inset: 0; z-index: 220; display: grid; place-items: center; padding: 20px; background: rgb(10 29 39 / .42); backdrop-filter: blur(3px); }
.model-settings-panel { width: min(480px, 100%); max-height: min(680px, calc(100dvh - 40px)); box-sizing: border-box; display: flex; flex-direction: column; border: 1px solid var(--line, #d8e3e7); border-radius: 16px; background: var(--panel, #fff); color: var(--ink, #18333c); box-shadow: 0 24px 80px rgb(8 28 40 / .26); outline: none; overflow: hidden; }
.model-settings-header { flex-shrink: 0; padding: 16px 20px 12px; display: flex; justify-content: space-between; align-items: flex-start; border-bottom: 1px solid var(--line); }
.model-settings-eyebrow { font-size: 11px; color: var(--primary); letter-spacing: .05em; }
.model-settings-header h2 { margin: 4px 0 0; font-size: 20px; }
.model-close { border: 0; background: transparent; font-size: 28px; color: var(--muted); width: 36px; height: 36px; cursor: pointer; }
.model-settings-body { min-height: 0; overflow-y: auto; overscroll-behavior: contain; padding: 0 20px 14px; }
.model-intro { font-size: 12px; line-height: 1.6; color: var(--muted); margin: 12px 0; }
.model-form { display: grid; gap: 7px; min-width: 0; }
.model-form > label { font-size: 13px; font-weight: 700; margin-top: 6px; }
.model-form > input, .model-form > select { min-width: 0; min-height: 40px; width: 100%; box-sizing: border-box; border: 1px solid var(--line); border-radius: 9px; background: var(--panel); color: var(--ink); padding: 8px 10px; font: inherit; font-size: 13px; }
.model-form input:focus-visible, .model-form select:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }
.model-form small { font-size: 11px; line-height: 1.7; color: var(--muted); font-weight: 400; }
.model-endpoint { display: grid; gap: 5px; padding: 10px 12px; border-radius: 9px; background: var(--surface-soft, #f4f7f8); font-size: 11px; color: var(--muted); }
.model-endpoint code { overflow-wrap: anywhere; font-size: 10px; }
.model-endpoint a { color: var(--primary); text-decoration: none; }
.model-key-saved { margin-left: 6px; color: var(--primary); font-size: 10px; font-weight: 500; }
.model-usage-note { margin: 4px 0 0; font-size: 11px; line-height: 1.6; color: var(--muted); overflow-wrap: anywhere; }
.model-warning, .model-success, .model-hint { padding: 10px 12px; border-radius: 8px; font-size: 12px; line-height: 1.7; }
.model-warning { background: #fff5e8; color: #854509; }
.model-success { background: #edf7f2; color: #24634d; }
.model-hint { padding: 0; color: var(--muted); }
.model-settings-actions { flex-shrink: 0; border-top: 1px solid var(--line); padding: 12px 20px; display: flex; justify-content: flex-end; gap: 8px; flex-wrap: wrap; }
.model-settings-actions button, .model-delete-confirm button, .model-secondary { border: 1px solid var(--line); border-radius: 9px; min-height: 40px; padding: 8px 14px; font: inherit; font-size: 12px; font-weight: 700; cursor: pointer; }
.model-primary { background: var(--primary); color: #fff; }
.model-secondary { background: var(--panel); color: var(--ink); }
.model-settings-actions .model-delete { margin-right: auto; color: #a04438; border-color: transparent; background: transparent; padding-left: 0; }
button:disabled { opacity: .45; cursor: not-allowed; }
button:focus-visible, a:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }
.model-delete-confirm { padding: 12px; border: 1px solid #e9c7c1; border-radius: 9px; display: flex; flex-wrap: wrap; gap: 8px; font-size: 12px; }
.model-delete-confirm span { width: 100%; line-height: 1.7; }
@media (max-width: 640px) { .model-settings-backdrop { padding: 10px; } .model-settings-panel { max-height: calc(100dvh - 20px); } .model-settings-header, .model-settings-actions { padding: 16px; } .model-settings-body { padding: 0 16px 16px; } }
</style>

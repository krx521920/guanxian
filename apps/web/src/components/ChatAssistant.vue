<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuth } from '../services/auth'
import { platformApi } from '../services/platform-api'
import type { KnowledgeCitation } from '../types/domain'
import { assistantErrorMessage, assistantModeLabel, assistantPhaseLabel, safeCitationUrl, shouldSendAssistantMessage } from './chat-assistant'
import AssistantRobotIcon from './AssistantRobotIcon.vue'
import PersonalModelSettings from './PersonalModelSettings.vue'
import AssistantBusinessResults from './AssistantBusinessResults.vue'
import { followupSelection, type BusinessResult, type SelectedEnterprise } from '../services/assistant-business-results'
import { personalModelApi, personalModelLabel, type ModelSettings } from '../services/personal-model'

interface ChatMessage {
  id: number
  role: 'assistant' | 'user'
  content: string
  citations: KnowledgeCitation[]
  traceId?: string
  mode?: string
  state?: 'pending' | 'streaming' | 'complete' | 'error' | 'stopped'
  phase?: string
  error?: string
  request?: { message: string; pageTitle: string; pagePath: string; selectedEnterprises: SelectedEnterprise[] }
  businessResults?: BusinessResult[]
}

const props = withDefaults(defineProps<{ workspace?: boolean }>(), { workspace: false })
const route = useRoute()
const auth = useAuth()
const open = ref(false)
const modelSettingsOpen = ref(false)
const chatLauncher = ref<HTMLButtonElement | null>(null)
const modelProvider = ref<string | null>(null)
const modelLauncher = ref<HTMLButtonElement | null>(null)
const modelStatus = ref('使用平台默认模式')
let modelStatusRevision = 0
const busy = ref(false)
const question = ref('')
const selectedEnterprises = ref<SelectedEnterprise[]>([])
const error = ref('')
const conversationId = ref(crypto.randomUUID())
const input = ref<HTMLTextAreaElement | null>(null)
const messageList = ref<HTMLElement | null>(null)
const activeMessageId = ref<number | null>(null)
const followLatest = ref(true)
const freshContext = ref(false)
const copiedId = ref<number | null>(null)
const responseDetail = ref<'AUTO' | 'BRIEF' | 'STANDARD' | 'DETAILED'>('AUTO')
const taskGoal = ref('')
let messageId = 0
let requestRevision = 0
let activeRequest: AbortController | null = null
let flushActive: (() => void) | null = null

const welcomeMessage = (): ChatMessage => ({
  id: ++messageId,
  role: 'assistant',
  content: '您好，我是管线智能助手。我会自动选择业务查询或知识库模式，只读取当前身份可见的数据，并在每条回答中标注模式。',
  citations: [],
  mode: 'AUTO',
})

const messages = ref<ChatMessage[]>([welcomeMessage()])
const hasConversation = computed(() => messages.value.length > 1)
const modelMonogram = computed(() => ({ DOUBAO: '豆', DEEPSEEK: 'D', KIMI: 'K', QWEN: 'Q' }[modelProvider.value || ''] || null))
const pageTitle = computed(() => String(route.meta.title || '当前页面'))
const allAssociations = computed(() => auth.user.value?.role === 'SYSTEM_ADMIN' && !auth.user.value.associationId)
const available = computed(() => Boolean(auth.user.value))
const latestMode = computed(() => [...messages.value].reverse().find((item) => item.role === 'assistant' && item.mode)?.mode || 'AUTO')
const statusText = computed(() => `${pageTitle.value} · ${allAssociations.value ? '全部协会 · ' : ''}${assistantModeLabel(latestMode.value)}`)
const quickQuestions = computed(() => {
  if (props.workspace) return ['现在有哪些会员企业？', '当前有哪些合作需求？', '当前有哪些协作事项？']
  if (route.path.startsWith('/policies')) {
    return ['资料中有哪些安全管理要求？', '哪些条款与会员企业有关？']
  }
  if (route.path.startsWith('/members')) {
    return ['现在有哪些会员企业？', '哪些会员企业有管线监测能力？', '会员企业需要重点关注哪些政策？']
  }
  if (route.path.startsWith('/matching')) {
    return ['当前有哪些生态匹配记录？', '供需匹配需要关注哪些合规要求？']
  }
  if (route.path.startsWith('/collaborations')) {
    return ['当前有哪些协作事项？', '进行中的协作事项有哪些？']
  }
  if (route.path.startsWith('/ecosystem')) {
    return ['当前有哪些产品与服务？', '当前有哪些合作需求？']
  }
  return ['概括当前资料库的核心要求', '有哪些内容值得会员企业关注？']
})

function toggle() {
  open.value = !open.value
  error.value = ''
  if (open.value) {
    void nextTick(() => input.value?.focus())
    void scrollToLatest()
    void refreshModelStatus()
  }
}

async function refreshModelStatus() {
  const revision = ++modelStatusRevision
  const userId = auth.user.value?.id
  try {
    const value = await personalModelApi.get()
    if (revision === modelStatusRevision && userId === auth.user.value?.id) {
      modelStatus.value = personalModelLabel(value)
      modelProvider.value = value.saved?.enabled ? value.saved.provider : null
    }
  } catch { if (revision === modelStatusRevision && userId === auth.user.value?.id) modelStatus.value = '模型配置状态暂不可用' }
}

function openModelSettings() {
  // Keep the same conversation mounted beneath the settings dialog.
  modelSettingsOpen.value = true
}

function closeModelSettings() {
  modelSettingsOpen.value = false
  void nextTick(() => modelLauncher.value?.focus())
}

function modelSettingsChanged(value: ModelSettings) {
  modelStatusRevision += 1
  clearConversation()
  modelStatus.value = personalModelLabel(value)
  modelProvider.value = value.saved?.enabled ? value.saved.provider : null
}

function close() {
  open.value = false
  error.value = ''
  void nextTick(() => chatLauncher.value?.focus())
}

function clearConversation() {
  activeRequest?.abort(new DOMException('用户取消当前回答', 'AbortError'))
  activeRequest = null
  requestRevision += 1
  busy.value = false
  activeMessageId.value = null
  error.value = ''
  question.value = ''
  conversationId.value = crypto.randomUUID()
  selectedEnterprises.value = []
  messages.value = [welcomeMessage()]
  followLatest.value = true
  freshContext.value = false
  taskGoal.value = ''
  if (!modelSettingsOpen.value) void nextTick(() => input.value?.focus())
}

async function scrollToLatest(force = false) {
  if (force) followLatest.value = true
  await nextTick()
  if (followLatest.value && messageList.value) messageList.value.scrollTop = messageList.value.scrollHeight
}

function trackScroll() {
  const list = messageList.value
  if (list) followLatest.value = list.scrollHeight - list.scrollTop - list.clientHeight < 48
}

function stopAnswer() {
  flushActive?.()
  const current = messages.value.find(item => item.id === activeMessageId.value)
  if (current) current.state = 'stopped'
  activeRequest?.abort(new DOMException('用户停止回答', 'AbortError'))
  activeRequest = null
  requestRevision += 1
  busy.value = false
  activeMessageId.value = null
  conversationId.value = crypto.randomUUID()
  freshContext.value = true
}

async function copyAnswer(message: ChatMessage) {
  try {
    await navigator.clipboard.writeText((message.state === 'error' || message.state === 'stopped' ? '[未完成回答]\n' : '') + message.content)
    copiedId.value = message.id
  } catch { error.value = '无法访问剪贴板，请选择回答文字手动复制。' }
}

function changeSelection(items: SelectedEnterprise[]) {
  if (busy.value) return
  selectedEnterprises.value = followupSelection(items)
  conversationId.value = crypto.randomUUID()
  freshContext.value = true
  void nextTick(() => input.value?.focus())
}

function selectForFollowup(items: SelectedEnterprise[]) {
  changeSelection(items)
  if (!busy.value && !question.value.trim()) question.value = items.length > 1
    ? '请根据当前登记资料，对比这几家企业的能力和产品服务。' : '这家企业有哪些已登记的能力和产品服务？'
}

async function ask(value = question.value, retry?: ChatMessage) {
  const normalized = value.trim()
  if (!normalized || busy.value || !available.value) return
  const revision = ++requestRevision
  question.value = ''
  error.value = ''
  if (!retry) messages.value.push({ id: ++messageId, role: 'user', content: normalized, citations: [] })
  const context = retry?.request || { message: normalized, pageTitle: pageTitle.value, pagePath: route.path, selectedEnterprises: followupSelection(selectedEnterprises.value) }
  selectedEnterprises.value = followupSelection(context.selectedEnterprises)
  const assistantMessage: ChatMessage = {
    id: retry?.id || ++messageId,
    role: 'assistant',
    content: '',
    citations: [],
    state: 'pending',
    request: context,
  }
  if (retry) messages.value.splice(messages.value.findIndex(item => item.id === retry.id), 1, assistantMessage)
  else messages.value.push(assistantMessage)
  activeMessageId.value = assistantMessage.id
  const controller = new AbortController()
  activeRequest = controller
  busy.value = true
  freshContext.value = false
  copiedId.value = null
  let pendingText = ''
  let frame: number | null = null
  const flush = () => {
    if (frame !== null) cancelAnimationFrame(frame)
    frame = null
    if (revision !== requestRevision) { pendingText = ''; return }
    const current = messages.value.find(item => item.id === assistantMessage.id)
    if (current && pendingText) {
      current.content += pendingText
      current.state = 'streaming'
      pendingText = ''
      void scrollToLatest()
    }
  }
  flushActive = flush
  await scrollToLatest(true)
  try {
    const answer = await platformApi.streamAssistant(
      normalized,
      conversationId.value,
      context.pageTitle,
      context.pagePath,
      (delta) => {
        if (revision !== requestRevision || controller.signal.aborted) return
        pendingText += delta
        if (frame === null) frame = requestAnimationFrame(flush)
      },
      controller.signal,
      5,
      auth.user.value?.associationId || undefined,
      (status) => {
        if (revision !== requestRevision || controller.signal.aborted) return
        const current = messages.value.find(item => item.id === assistantMessage.id)
        if (current) { current.mode = status.mode; current.phase = status.phase }
      },
      { responseDetail: responseDetail.value, taskGoal: taskGoal.value.trim(), selectedEnterpriseIds: context.selectedEnterprises.map(item => item.id) },
      (results) => {
        if (revision !== requestRevision || controller.signal.aborted) return
        const current = messages.value.find(item => item.id === assistantMessage.id)
        if (current) {
          current.businessResults = [...new Map([...(current.businessResults || []), ...results].map(result => [result.id, result])).values()]
          const refreshed = results.find(result => result.kind === 'SELECTED_MEMBERS' && result.status === 'OK')
          if (refreshed) selectedEnterprises.value = followupSelection(refreshed.items)
          void scrollToLatest()
        }
      },
    )
    if (revision !== requestRevision) return
    flush()
    const current = messages.value.find((item) => item.id === assistantMessage.id)
    if (current) {
      current.content = answer.answer
      current.citations = answer.citations
      current.traceId = answer.traceId
      current.mode = answer.mode
      current.state = 'complete'
    }
  } catch (reason) {
    if (revision === requestRevision) {
      flush()
      const current = messages.value.find(item => item.id === assistantMessage.id)
      if (current) {
        current.state = controller.signal.aborted ? 'stopped' : 'error'
        current.error = controller.signal.aborted ? undefined : assistantErrorMessage(reason)
      }
      // A failed server turn may already have entered memory; never silently reuse it.
      conversationId.value = crypto.randomUUID()
      freshContext.value = true
    }
  } finally {
    if (frame !== null) cancelAnimationFrame(frame)
    if (flushActive === flush) flushActive = null
    if (revision === requestRevision) {
      busy.value = false
      activeMessageId.value = null
    }
    if (activeRequest === controller) activeRequest = null
    await scrollToLatest()
  }
}

function handleComposerKeydown(event: KeyboardEvent) {
  if (shouldSendAssistantMessage(event)) {
    event.preventDefault()
    void ask()
  }
}

function handleEscape(event: KeyboardEvent) {
  if (event.key === 'Escape' && !event.defaultPrevented && !modelSettingsOpen.value
    && !document.querySelector('[role="dialog"][aria-modal="true"]') && open.value) close()
}

// Watch primitive sources separately: a renewed (but identical) SessionUser object
// must not be mistaken for an account/scope switch and erase an unsent draft.
watch([
  () => auth.user.value?.id,
  () => auth.user.value?.associationId,
  () => auth.user.value?.enterpriseId,
  () => auth.user.value?.role,
], () => {
  activeRequest?.abort(new DOMException('协会范围已切换', 'AbortError'))
  activeRequest = null
  requestRevision += 1
  busy.value = false
  activeMessageId.value = null
  error.value = ''
  conversationId.value = crypto.randomUUID()
  messages.value = [welcomeMessage()]
  selectedEnterprises.value = []
  question.value = ''
  freshContext.value = false
  followLatest.value = true
  taskGoal.value = ''
  responseDetail.value = 'AUTO'
  modelStatus.value = '使用平台默认模式'
  modelProvider.value = null
  modelSettingsOpen.value = false
  modelStatusRevision += 1
  if (props.workspace) void refreshModelStatus()
})

watch(() => props.workspace, (workspace) => {
  open.value = false
  if (workspace) {
    void refreshModelStatus()
    void scrollToLatest()
  }
}, { immediate: true })
watch(() => route.path, () => {
  open.value = false
  modelSettingsOpen.value = false
})

window.addEventListener('keydown', handleEscape)
onBeforeUnmount(() => {
  activeRequest?.abort(new DOMException('页面已卸载', 'AbortError'))
  activeRequest = null
  requestRevision += 1
  window.removeEventListener('keydown', handleEscape)
})
</script>

<template>
  <div class="assistant-root" :class="{ 'assistant-workspace': workspace, 'workspace-idle': workspace && !hasConversation }">
    <header v-if="workspace" class="workspace-intro">
      <span class="workspace-eyebrow"><i /> ASSOCIATION WORKSPACE</span>
      <h1>{{ hasConversation ? '让每一步，都有据可循' : '从一个问题开始' }}</h1>
      <p>查会员、读政策、跟进协作。把问题交给助手，把判断留给您。</p>
    </header>
    <PersonalModelSettings v-if="modelSettingsOpen" @close="closeModelSettings" @changed="modelSettingsChanged" />
    <section
      v-if="workspace || open"
      :inert="modelSettingsOpen"
      id="platform-chat-assistant"
      class="assistant-panel"
      :role="workspace ? 'region' : 'dialog'"
      :aria-modal="workspace ? undefined : 'false'"
      :aria-label="workspace ? '管线智能助手' : undefined"
      :aria-labelledby="workspace ? undefined : 'assistant-title'"
    >
      <header v-if="!workspace || hasConversation" class="assistant-header">
        <template v-if="!workspace">
          <span class="assistant-mark" aria-hidden="true"><AssistantRobotIcon /></span>
          <div>
            <strong id="assistant-title">管线智能助手</strong>
            <small>{{ statusText }}</small>
          </div>
        </template>
        <button class="assistant-clear" type="button" :disabled="messages.length === 1" @click="clearConversation">{{ busy ? '取消并清空' : '清空' }}</button>
        <button v-if="!workspace" class="assistant-close" type="button" aria-label="关闭智能助手" @click="close">×</button>
      </header>

      <div v-show="!workspace || hasConversation" ref="messageList" class="assistant-messages" aria-label="聊天记录" @scroll="trackScroll">
        <article v-for="message in messages" :key="message.id" class="assistant-message" :class="message.role">
          <span class="assistant-role">{{ message.role === 'assistant' ? '助手' : '您' }}</span>
          <div class="assistant-bubble">
            <small v-if="message.request?.selectedEnterprises.length" class="assistant-selection-note">本轮所选：{{ message.request.selectedEnterprises.map(item => item.name).join('、') }}（名称为选择时快照）</small>
            <div v-if="message.role === 'assistant' && message.id === activeMessageId && !message.content" class="assistant-thinking">
              <i /><i /><i /><span>{{ assistantPhaseLabel(message.phase || '') }}</span>
            </div>
            <p v-else>{{ message.content }}</p>
            <small v-if="message.state === 'streaming'" class="assistant-progress">正在输出 · 内容尚未完成</small>
            <p v-if="message.state === 'error'" class="assistant-result-note" role="alert">{{ message.error }}{{ message.content ? ' 已保留收到的内容，请勿视为完整结论。' : '' }}</p>
            <p v-if="message.state === 'stopped'" class="assistant-result-note">已停止{{ message.content ? '，以上为未完成内容。' : '，尚未收到回答正文。' }}</p>
            <span v-if="message.role === 'assistant' && message.mode" class="assistant-mode">{{ assistantModeLabel(message.mode) }}</span>
            <details v-if="message.citations.length" class="assistant-citations">
              <summary>{{ message.citations.length }} 条检索参考资料</summary>
              <small>检索结果不代表回答逐条采用，请按正文引用编号核对。</small>
              <ol>
                <li v-for="citation in message.citations" :key="citation.chunkId">
                  <strong>{{ citation.documentName }}</strong>
                  <span>{{ citation.quote }}</span>
                  <a
                    v-if="safeCitationUrl(citation.source)"
                    :href="safeCitationUrl(citation.source) || undefined"
                    target="_blank"
                    rel="noopener noreferrer"
                  >查看来源 ↗</a>
                </li>
              </ol>
            </details>
            <small v-if="message.traceId" class="assistant-trace">追踪编号 {{ message.traceId }}</small>
            <AssistantBusinessResults v-if="message.businessResults?.length" :results="message.businessResults" :incomplete="message.state !== 'complete'" :followup-disabled="busy || !available" @followup="selectForFollowup" />
            <div v-if="message.role === 'assistant' && message.state && !['pending', 'streaming'].includes(message.state)" class="assistant-actions">
              <button v-if="message.content" type="button" @click="copyAnswer(message)">{{ copiedId === message.id ? '已复制' : '复制回答' }}</button>
              <button v-if="['error', 'stopped'].includes(message.state) && message.id === messages[messages.length - 1]?.id" type="button" :disabled="busy || !available" @click="ask(message.request?.message, message)">重新回答（新会话）</button>
            </div>
          </div>
        </article>
      </div>

      <div class="assistant-notices">
        <button v-if="!followLatest" type="button" class="assistant-jump" @click="scrollToLatest(true)">查看最新回答 ↓</button>
        <p v-if="freshContext" class="assistant-context-note">下一次提问将使用新会话；所选企业仍会重新核验。</p>
      </div>

      <div v-if="!workspace && messages.length === 1" class="assistant-prompts" aria-label="快捷问题">
        <button v-for="item in quickQuestions" :key="item" type="button" :disabled="!available" @click="ask(item)">{{ item }}</button>
      </div>

      <p v-if="error" class="assistant-error" role="alert">{{ error }}</p>

      <details class="assistant-preferences">
        <summary>回答设置 · {{ { AUTO: '自动详略', BRIEF: '简洁', STANDARD: '标准', DETAILED: '详细' }[responseDetail] }}{{ taskGoal.trim() ? ' · 已固定任务目标' : '' }}</summary>
        <label>输出详略
          <select v-model="responseDetail" :disabled="busy" aria-label="输出详略">
            <option value="AUTO">自动</option><option value="BRIEF">简洁</option><option value="STANDARD">标准</option><option value="DETAILED">详细</option>
          </select>
        </label>
        <label>固定任务目标与约束（可选）
          <textarea v-model="taskGoal" :disabled="busy" maxlength="400" rows="2" aria-label="固定任务目标与约束" placeholder="例如：准备客户演示，只用虚构企业数据，先查现状再列缺口。" />
        </label>
        <small>AI 模式保留有限近期对话，不保存全部历史。关键约束可固定在这里；新话题请清空。模型未接通时，这些设置不会让本地规则查询具备推理能力。</small>
      </details>

        <section v-if="selectedEnterprises.length" class="assistant-selection" aria-label="追问企业范围">
          <div><strong>正在追问 {{ selectedEnterprises.length }} 家企业</strong><button type="button" :disabled="busy" @click="changeSelection([])">清除选择</button></div>
          <ul><li v-for="item in selectedEnterprises" :key="item.id"><span>{{ item.name }}</span><button type="button" :disabled="busy" :aria-label="`移除 ${item.name}`" @click="changeSelection(selectedEnterprises.filter(selected => selected.id !== item.id))">×</button></li></ul>
          <small>每轮重新核验权限和资料；不会自动发送。换话题可清除选择。</small>
        </section>
      <form class="assistant-composer" @submit.prevent="ask()">
        <textarea
          ref="input"
          v-model="question"
          rows="2"
          maxlength="2000"
          :disabled="busy || !available"
          :placeholder="workspace ? '今天，有什么业务问题想了解？' : '输入问题，Enter 发送，Shift + Enter 换行'"
          aria-label="向管线智能助手提问"
          @keydown="handleComposerKeydown"
        />
        <div class="assistant-composer-actions">
          <button ref="modelLauncher" class="assistant-model-icon" type="button" aria-label="打开个人模型接入"
            :title="`${modelStatus} · 模型接入`" :aria-expanded="modelSettingsOpen" aria-haspopup="dialog" @click="openModelSettings">
            <span v-if="modelMonogram" class="model-monogram" aria-hidden="true">{{ modelMonogram }}</span>
            <svg v-else viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 8 4.5v9L12 21l-8-4.5v-9L12 3Z M4 7.5l8 4.5 8-4.5M12 12v9M8 5.3l8 4.5" /></svg>
          </button>
          <button v-if="busy" class="assistant-submit" type="button" aria-label="停止回答" title="停止回答，保留已生成内容" @click="stopAnswer"><span aria-hidden="true">■</span></button>
          <button v-else class="assistant-submit" type="submit" :disabled="!available || !question.trim()" aria-label="发送问题">
            <span v-if="workspace">开始对话</span>
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m4 4 16 8-16 8 3-8-3-8Z"/><path d="M7 12h13"/></svg>
          </button>
        </div>
      </form>
      <footer><span class="assistant-model-status">{{ modelStatus }}</span>只读查询 · 不会代替您执行系统操作</footer>
    </section>

    <nav v-if="workspace && !hasConversation" class="workspace-prompts" aria-label="工作台快捷问题">
      <span>试着问问</span>
      <button v-for="item in quickQuestions" :key="item" type="button" :disabled="!available" @click="ask(item)">{{ item }} <span aria-hidden="true">↗</span></button>
    </nav>
    <button
      v-if="!workspace"
      ref="chatLauncher"
      class="assistant-launcher"
      type="button"
      aria-controls="platform-chat-assistant"
      :aria-expanded="open"
      :aria-label="open ? '关闭管线智能助手' : '打开管线智能助手'"
      @click="toggle"
    >
      <AssistantRobotIcon v-if="!open" />
      <span v-else aria-hidden="true">×</span>
    </button>
  </div>
</template>

<style scoped>
.assistant-selection{margin:8px 14px 0;font-size:12px;padding:9px 11px;background:var(--primary-soft);border-radius:10px;max-height:150px;overflow:auto}.assistant-selection>div{display:flex;justify-content:space-between;gap:8px;align-items:center}.assistant-selection ul{list-style:none;margin:6px 0;padding:0;display:flex;flex-wrap:wrap;gap:6px}.assistant-selection li{display:flex;align-items:center;gap:4px;max-width:100%;border:1px solid var(--line);border-radius:7px;padding:3px 6px;background:var(--panel)}.assistant-selection li span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:240px}.assistant-selection button{padding:2px 5px;font:inherit;background:transparent;color:var(--primary);border:0;cursor:pointer;flex-shrink:0}.assistant-selection button:disabled{opacity:.45;cursor:not-allowed}.assistant-selection-note{display:block;color:var(--muted);margin-bottom:8px;overflow-wrap:anywhere}
.assistant-root { position: relative; z-index: 75; }
.assistant-launcher { position: fixed; right: 24px; bottom: max(22px, env(safe-area-inset-bottom)); min-width: 56px; width: 56px; height: 56px; padding: 0; border: 1px solid color-mix(in srgb, var(--primary) 80%, #fff); border-radius: 20px; background: var(--primary); color: #fff; box-shadow: 0 8px 24px color-mix(in srgb, var(--primary) 23%, transparent); display: inline-flex; align-items: center; justify-content: center; cursor: pointer; font: inherit; font-weight: 700; }
.assistant-model-status { display: block; margin-bottom: 4px; color: var(--primary); }
.assistant-launcher:hover { filter: brightness(1.06); transform: translateY(-1px); }
.assistant-launcher:focus-visible { outline: 3px solid color-mix(in srgb, var(--primary) 28%, transparent); outline-offset: 3px; }
.assistant-launcher svg { width: 27px; height: 27px; fill: none; stroke: currentColor; stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
.assistant-launcher[aria-expanded="true"] { font-size: 24px; }
.assistant-panel { position: fixed; right: 24px; bottom: calc(max(22px, env(safe-area-inset-bottom)) + 68px); width: min(480px, calc(100vw - 40px)); height: min(700px, calc(100dvh - 116px)); border: 1px solid var(--line); border-radius: 22px; overflow: hidden; background: var(--panel); color: var(--ink); box-shadow: 0 24px 80px rgba(8, 28, 40, .17); display: flex; flex-direction: column; }
.assistant-panel > * { flex-shrink: 0; }
.assistant-panel > .assistant-messages { flex: 1 1 auto; }
.assistant-progress { display: block; color: var(--muted); margin-top: 6px; }
.assistant-result-note { color: #9a3412; padding-top: 8px; font-size: 11px; }
.assistant-actions { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 8px; }
.assistant-actions button, .assistant-jump { font: inherit; font-size: 11px; cursor: pointer; border: 1px solid var(--line); border-radius: 8px; background: var(--panel); color: var(--primary); padding: 5px 8px; }
.assistant-jump { display: block; margin: 4px auto; }
.assistant-preferences { margin: 6px 14px 0; color: var(--muted); font-size: 11px; max-height: 190px; overflow-y: auto; }
.assistant-preferences summary { cursor: pointer; color: var(--primary); }
.assistant-preferences label { display: block; margin: 8px 0; }
.assistant-preferences select { margin-left: 10px; border: 1px solid var(--line); border-radius: 5px; padding: 3px; font: inherit; }
.assistant-preferences textarea { display: block; width: 100%; margin-top: 4px; border: 1px solid var(--line); border-radius: 6px; padding: 6px; resize: vertical; font: inherit; }
.assistant-header { min-width: 0; padding: 14px 16px; border-bottom: 1px solid var(--line); background: var(--panel); display: grid; grid-template-columns: 34px minmax(0, 1fr) auto 30px; align-items: center; gap: 8px; }
.assistant-mark { width: 32px; height: 32px; border-radius: 11px; background: var(--primary-soft); color: var(--primary); display: grid; place-items: center; }
.assistant-mark svg { width: 21px; height: 21px; fill: none; stroke: currentColor; stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
.assistant-header div { min-width: 0; display: grid; gap: 3px; }
.assistant-header strong { font-size: 14px; }
.assistant-header small { overflow: hidden; color: var(--muted); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.assistant-clear, .assistant-close { border: 0; background: transparent; color: var(--muted); cursor: pointer; font: inherit; }
.assistant-clear { padding: 6px; font-size: 11px; }
.assistant-clear:disabled { cursor: default; opacity: .4; }
.assistant-close { width: 30px; height: 30px; border-radius: 8px; font-size: 21px; line-height: 1; }
.assistant-close:hover { background: var(--primary-soft); color: var(--ink); }
.assistant-messages { min-height: 0; padding: 18px 16px 8px; overflow-y: auto; overscroll-behavior: contain; display: flex; flex-direction: column; gap: 14px; }
.assistant-message { max-width: 88%; display: grid; gap: 5px; }
.assistant-message.user { align-self: flex-end; justify-items: end; }
.assistant-role { padding: 0 4px; color: var(--muted); font-size: 9px; }
.assistant-bubble { padding: 11px 13px; border: 1px solid var(--line); border-radius: 5px 13px 13px 13px; background: var(--surface-soft); font-size: 13px; line-height: 1.7; }
.assistant-message.user .assistant-bubble { border-color: transparent; border-radius: 13px 5px 13px 13px; background: var(--primary); color: #fff; }
.assistant-bubble p { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.assistant-mode { display: inline-flex; margin-top: 8px; padding: 3px 7px; border-radius: 999px; background: var(--primary-soft); color: var(--primary); font-size: 9px; font-weight: 700; }
.assistant-citations { margin-top: 10px; padding-top: 8px; border-top: 1px solid var(--line); color: var(--muted); }
.assistant-citations summary { cursor: pointer; color: var(--primary); font-size: 10px; font-weight: 700; }
.assistant-citations ol { margin: 8px 0 0; padding-left: 18px; display: grid; gap: 10px; }
.assistant-citations li { padding-left: 2px; }
.assistant-citations strong, .assistant-citations span { display: block; }
.assistant-citations strong { color: var(--ink); font-size: 10px; }
.assistant-citations span { margin-top: 3px; font-size: 10px; line-height: 1.55; }
.assistant-citations a { display: inline-block; margin-top: 4px; color: var(--primary); font-size: 10px; text-decoration: none; }
.assistant-trace { display: block; margin-top: 8px; color: var(--muted); font-size: 9px; overflow-wrap: anywhere; }
.assistant-thinking { display: flex; align-items: center; gap: 4px; color: var(--muted); }
.assistant-thinking i { width: 5px; height: 5px; border-radius: 50%; background: var(--primary); animation: assistant-pulse 1.2s infinite ease-in-out; }
.assistant-thinking i:nth-child(2) { animation-delay: .16s; }
.assistant-thinking i:nth-child(3) { animation-delay: .32s; }
.assistant-thinking span { margin-left: 4px; font-size: 10px; }
.assistant-prompts { padding: 8px 16px 4px; display: flex; gap: 7px; overflow-x: auto; }
.assistant-prompts button { flex: 0 0 auto; max-width: 245px; padding: 7px 10px; border: 1px solid var(--line); border-radius: 14px; background: var(--panel); color: var(--primary); cursor: pointer; font: inherit; font-size: 10px; white-space: nowrap; }
.assistant-prompts button:hover { border-color: var(--primary); background: var(--primary-soft); }
.assistant-prompts button:disabled { cursor: not-allowed; opacity: .5; }
.assistant-error, .assistant-context-note { margin: 7px 16px 0; padding: 9px 10px; border-radius: 8px; font-size: 10px; line-height: 1.55; }
.assistant-error { color: #9a3412; background: #fff2e8; }
.assistant-context-note { color: var(--muted); background: var(--primary-soft); }
.assistant-composer { margin: 10px 14px 0; padding: 5px 5px 5px 11px; border: 1px solid var(--line); border-radius: 16px; background: var(--panel); display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: end; gap: 6px; }
.assistant-composer-actions { display: flex; align-items: center; justify-content: flex-end; gap: 8px; }
.assistant-composer-actions > button { flex-shrink: 0; }
.assistant-composer:focus-within { border-color: var(--primary); box-shadow: 0 0 0 3px color-mix(in srgb, var(--primary) 12%, transparent); }
.assistant-composer textarea { min-height: 40px; max-height: 100px; padding: 6px 0; border: 0; outline: 0; resize: none; background: transparent; color: var(--ink); font: inherit; font-size: 12px; line-height: 1.5; }
.assistant-composer textarea::placeholder { color: var(--muted); }
.assistant-submit { width: 36px; height: 36px; border: 0; border-radius: 10px; background: var(--primary); color: #fff; cursor: pointer; display: grid; place-items: center; }
.assistant-composer button:disabled { cursor: not-allowed; opacity: .45; }
.assistant-composer svg { width: 18px; height: 18px; fill: none; stroke: currentColor; stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
.assistant-panel footer { padding: 8px 14px 11px; color: var(--muted); font-size: 9px; text-align: center; }
@keyframes assistant-pulse { 0%, 70%, 100% { opacity: .25; transform: translateY(0); } 35% { opacity: 1; transform: translateY(-2px); } }
@media (prefers-reduced-motion: reduce) {
  .assistant-launcher { transition: none; }
  .assistant-thinking i { animation: none; opacity: .7; }
}

/* One conversation: a workspace canvas at home, a compact robot elsewhere. */
.assistant-model-icon { width: 36px; height: 36px; padding: 7px; border-radius: 11px; border: 1px solid var(--line); background: var(--panel); color: var(--primary); display: grid; place-items: center; cursor: pointer; }
.assistant-model-icon svg { width: 20px; height: 20px; fill: none; stroke: currentColor; stroke-width: 1.7; stroke-linecap: round; stroke-linejoin: round; }
.assistant-model-icon:hover { background: var(--primary-soft); border-color: var(--primary); }
.model-monogram { font-size: 15px; font-weight: 750; }
.assistant-model-icon:focus-visible, .workspace-prompts button:focus-visible { outline: 3px solid var(--primary); outline-offset: 3px; }
.assistant-workspace { position: relative; z-index: auto; max-width: 1000px; margin: 0 auto 38px; }
.workspace-intro { padding: clamp(24px, 5vh, 62px) 12px 36px; text-align: center; }
.workspace-eyebrow { display: inline-flex; align-items: center; gap: 8px; font-size: 10px; font-weight: 650; letter-spacing: .18em; color: var(--primary); }
.workspace-eyebrow i { width: 6px; height: 6px; border-radius: 50%; background: var(--primary); }
.workspace-intro h1 { margin: 17px 0 16px; color: var(--ink); font: 600 clamp(32px, 4vw, 54px)/1.25 "Noto Serif SC", "Songti SC", SimSun, serif; letter-spacing: .045em; }
.workspace-intro p { margin: 0; color: var(--muted); font-size: 14px; line-height: 1.8; }
.assistant-workspace .assistant-panel { position: relative; left: auto; right: auto; bottom: auto; width: 100%; height: auto; min-height: 240px; border: 1px solid color-mix(in srgb, var(--line) 65%, transparent); border-radius: 26px; box-shadow: 0 10px 40px rgba(22, 42, 45, .06); overflow: hidden; }
.assistant-workspace .assistant-header { grid-template-columns: minmax(0, 1fr); justify-items: end; padding: 12px 24px 0; border: 0; }
.assistant-workspace .assistant-messages { min-height: 180px; height: min(46vh, 480px); flex: none; padding: 16px 24px; }
.assistant-workspace .assistant-message { max-width: 96%; }
.assistant-workspace .assistant-message.user { max-width: 85%; }
.assistant-workspace .assistant-bubble { font-size: 14px; padding: 15px 18px; line-height: 1.9; }
.assistant-workspace .assistant-preferences { order: 4; margin: 0 26px; font-size: 11px; }
.assistant-workspace .assistant-composer { order: 3; grid-template-columns: minmax(0, 1fr); align-items: end; margin: 8px 24px 14px; padding: 16px 16px 12px; border-color: var(--line); border-radius: 16px; }
.assistant-workspace .assistant-composer textarea { min-height: 60px; font-size: 15px; line-height: 1.8; }
.assistant-workspace .assistant-submit { width: auto; min-width: 116px; height: 40px; display: inline-flex; align-items: center; justify-content: center; gap: 12px; padding: 0 17px; border-radius: 22px; font-family: inherit; font-size: 13px; font-weight: 600; line-height: 1; }
.assistant-workspace .assistant-model-icon { width: 40px; height: 40px; }
.assistant-workspace .assistant-panel footer { order: 6; font-size: 10px; padding: 12px 24px 16px; }
.assistant-workspace .assistant-model-status { display: inline; margin-right: 12px; }
.workspace-idle .assistant-composer { min-height: 150px; border-color: transparent; padding: 8px 0; margin-top: 16px; }
.workspace-idle .assistant-composer:focus-within { border-color: transparent; box-shadow: none; }
.workspace-idle .assistant-preferences { margin-top: 2px; }
.workspace-prompts { display: flex; flex-wrap: wrap; align-items: center; justify-content: center; gap: 9px; margin-top: 19px; }
.workspace-prompts > span { color: var(--muted); font-size: 11px; margin-right: 3px; }
.workspace-prompts button { display: inline-flex; gap: 12px; border: 1px solid var(--line); border-radius: 18px; background: transparent; color: var(--muted); padding: 8px 13px; cursor: pointer; font: inherit; font-size: 11px; }
.workspace-prompts button:hover { color: var(--primary); border-color: var(--primary); background: var(--primary-soft); }
.workspace-prompts button:disabled { opacity: .5; cursor: not-allowed; }
@media (max-width: 640px) {
  .assistant-launcher { left: auto; right: 14px; bottom: max(14px, env(safe-area-inset-bottom)); width: 50px; min-width: 50px; height: 50px; border-radius: 17px; }
  .assistant-launcher[aria-expanded="true"] { width: 50px; min-width: 50px; }
  .assistant-panel { left: auto; right: 10px; bottom: calc(max(14px, env(safe-area-inset-bottom)) + 62px); width: calc(100vw - 20px); height: min(680px, calc(100dvh - 92px)); }
  .assistant-workspace { margin-bottom: 26px; }
  .workspace-intro { padding: 24px 3px 26px; }
  .workspace-intro h1 { font-size: 32px; margin: 12px 0; }
  .workspace-intro p { font-size: 12px; max-width: 260px; margin: auto; }
  .assistant-workspace .assistant-panel { border-radius: 20px; }
  .assistant-workspace .assistant-header { padding: 14px 14px 8px; gap: 6px; }
  .assistant-workspace .assistant-messages { padding: 12px; height: min(44vh, 380px); }
  .assistant-workspace .assistant-composer { margin: 4px 14px 12px; padding: 8px; grid-template-columns: minmax(0, 1fr); }
  .assistant-workspace .assistant-composer textarea { min-height: 72px; }
  .assistant-workspace .assistant-submit { min-width: 104px; font-size: 12px; }
  .assistant-workspace .assistant-preferences { margin: 0 16px; }
  .assistant-workspace .assistant-panel footer { padding: 10px 14px 14px; font-size: 9px; }
  .workspace-prompts { gap: 7px; }
  .workspace-prompts > span { display: none; }
  .workspace-prompts button { font-size: 10px; padding: 7px 10px; }
}
</style>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuth } from '../services/auth'
import { platformApi } from '../services/platform-api'
import type { KnowledgeCitation } from '../types/domain'
import { assistantErrorMessage, safeCitationUrl } from './chat-assistant'

interface ChatMessage {
  id: number
  role: 'assistant' | 'user'
  content: string
  citations: KnowledgeCitation[]
  traceId?: string
  mode?: string
}

const route = useRoute()
const auth = useAuth()
const open = ref(false)
const busy = ref(false)
const question = ref('')
const error = ref('')
const conversationId = ref(crypto.randomUUID())
const input = ref<HTMLTextAreaElement | null>(null)
const messageList = ref<HTMLElement | null>(null)
let messageId = 0
let requestRevision = 0

const welcomeMessage = (): ChatMessage => ({
  id: ++messageId,
  role: 'assistant',
  content: '您好，我是管线智能助手。我会依据您当前有权查看的协会资料回答，并为答案标注出处。',
  citations: [],
})

const messages = ref<ChatMessage[]>([welcomeMessage()])
const pageTitle = computed(() => String(route.meta.title || '当前页面'))
const requiresAssociation = computed(() => auth.user.value?.role === 'SYSTEM_ADMIN' && !auth.user.value.associationId)
const available = computed(() => !requiresAssociation.value)
const statusText = computed(() => requiresAssociation.value ? '请先在左侧选择管理协会' : `${pageTitle.value} · 只读问答`)
const quickQuestions = computed(() => {
  if (route.path.startsWith('/policies')) {
    return ['资料中有哪些安全管理要求？', '哪些条款与会员企业有关？']
  }
  if (route.path.startsWith('/members')) {
    return ['会员企业需要重点关注哪些政策？', '资料中有哪些企业合规要求？']
  }
  if (route.path.startsWith('/matching')) {
    return ['供需匹配需要关注哪些合规要求？', '资料中对项目合作有哪些规定？']
  }
  return ['概括当前资料库的核心要求', '有哪些内容值得会员企业关注？']
})

function toggle() {
  open.value = !open.value
  error.value = ''
  if (open.value) void nextTick(() => input.value?.focus())
}

function close() {
  open.value = false
  error.value = ''
}

function clearConversation() {
  requestRevision += 1
  busy.value = false
  error.value = ''
  question.value = ''
  conversationId.value = crypto.randomUUID()
  messages.value = [welcomeMessage()]
  void nextTick(() => input.value?.focus())
}

async function scrollToLatest() {
  await nextTick()
  if (messageList.value) messageList.value.scrollTop = messageList.value.scrollHeight
}

async function ask(value = question.value) {
  const normalized = value.trim()
  if (!normalized || busy.value || !available.value) return
  const revision = ++requestRevision
  question.value = ''
  error.value = ''
  messages.value.push({ id: ++messageId, role: 'user', content: normalized, citations: [] })
  busy.value = true
  await scrollToLatest()
  try {
    const answer = await platformApi.chatWithAssistant(
      normalized,
      conversationId.value,
      pageTitle.value,
      route.path,
      5,
      auth.user.value?.associationId || undefined,
    )
    if (revision !== requestRevision) return
    messages.value.push({
      id: ++messageId,
      role: 'assistant',
      content: answer.answer,
      citations: answer.citations,
      traceId: answer.traceId,
      mode: answer.mode,
    })
  } catch (reason) {
    if (revision === requestRevision) error.value = assistantErrorMessage(reason)
  } finally {
    if (revision === requestRevision) busy.value = false
    await scrollToLatest()
  }
}

function handleComposerKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    void ask()
  }
}

function handleEscape(event: KeyboardEvent) {
  if (event.key === 'Escape' && open.value) close()
}

watch(() => auth.user.value?.associationId, () => {
  requestRevision += 1
  busy.value = false
  error.value = ''
  conversationId.value = crypto.randomUUID()
  messages.value = [welcomeMessage()]
})

window.addEventListener('keydown', handleEscape)
onBeforeUnmount(() => {
  requestRevision += 1
  window.removeEventListener('keydown', handleEscape)
})
</script>

<template>
  <div class="assistant-root">
    <section
      v-if="open"
      id="platform-chat-assistant"
      class="assistant-panel"
      role="dialog"
      aria-modal="false"
      aria-labelledby="assistant-title"
    >
      <header class="assistant-header">
        <span class="assistant-mark" aria-hidden="true">
          <svg viewBox="0 0 24 24"><path d="M12 3a7 7 0 0 0-7 7v1a4 4 0 0 0 0 8h2v-7H5v-2a7 7 0 0 1 14 0v2h-2v7h2a2 2 0 0 1-2 2h-3"/><path d="M9 17c.8.7 1.8 1 3 1s2.2-.3 3-1"/></svg>
        </span>
        <div>
          <strong id="assistant-title">管线智能助手</strong>
          <small>{{ statusText }}</small>
        </div>
        <button class="assistant-clear" type="button" :disabled="busy || messages.length === 1" @click="clearConversation">清空</button>
        <button class="assistant-close" type="button" aria-label="关闭智能助手" @click="close">×</button>
      </header>

      <div ref="messageList" class="assistant-messages" aria-live="polite">
        <article v-for="message in messages" :key="message.id" class="assistant-message" :class="message.role">
          <span class="assistant-role">{{ message.role === 'assistant' ? '助手' : '您' }}</span>
          <div class="assistant-bubble">
            <p>{{ message.content }}</p>
            <details v-if="message.citations.length" class="assistant-citations">
              <summary>{{ message.citations.length }} 条引用依据</summary>
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
          </div>
        </article>
        <article v-if="busy" class="assistant-message assistant">
          <span class="assistant-role">助手</span>
          <div class="assistant-bubble assistant-thinking"><i /><i /><i /><span>正在检索可见资料</span></div>
        </article>
      </div>

      <div v-if="messages.length === 1" class="assistant-prompts" aria-label="快捷问题">
        <button v-for="item in quickQuestions" :key="item" type="button" :disabled="!available" @click="ask(item)">{{ item }}</button>
      </div>

      <p v-if="error" class="assistant-error" role="alert">{{ error }}</p>
      <p v-if="requiresAssociation" class="assistant-context-note">系统管理员需先从左侧选择管理协会，问答内容才会按该协会隔离。</p>

      <form class="assistant-composer" @submit.prevent="ask()">
        <textarea
          ref="input"
          v-model="question"
          rows="2"
          maxlength="2000"
          :disabled="busy || !available"
          :placeholder="available ? '输入问题，Enter 发送，Shift + Enter 换行' : '请先选择管理协会'"
          aria-label="向管线智能助手提问"
          @keydown="handleComposerKeydown"
        />
        <button type="submit" :disabled="busy || !available || !question.trim()" aria-label="发送问题">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m4 4 16 8-16 8 3-8-3-8Z"/><path d="M7 12h13"/></svg>
        </button>
      </form>
      <footer>仅依据当前身份可见资料回答 · 不会代替您执行系统操作</footer>
    </section>

    <button
      class="assistant-launcher"
      type="button"
      aria-controls="platform-chat-assistant"
      :aria-expanded="open"
      :aria-label="open ? '关闭管线智能助手' : '打开管线智能助手'"
      @click="toggle"
    >
      <svg v-if="!open" viewBox="0 0 24 24" aria-hidden="true"><path d="M21 15a4 4 0 0 1-4 4H8l-5 3V7a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4Z"/><path d="M8 9h8M8 13h5"/></svg>
      <span v-if="!open">智能助手</span>
      <span v-else aria-hidden="true">×</span>
    </button>
  </div>
</template>

<style scoped>
.assistant-root { position: relative; z-index: 75; }
.assistant-launcher { position: fixed; right: 24px; bottom: 24px; min-width: 132px; height: 48px; padding: 0 18px; border: 1px solid color-mix(in srgb, var(--primary) 80%, #fff); border-radius: 24px; background: var(--primary); color: #fff; box-shadow: 0 12px 30px rgba(20, 61, 76, .22); display: inline-flex; align-items: center; justify-content: center; gap: 9px; cursor: pointer; font: inherit; font-size: 13px; font-weight: 700; }
.assistant-launcher:hover { filter: brightness(1.06); transform: translateY(-1px); }
.assistant-launcher:focus-visible { outline: 3px solid color-mix(in srgb, var(--primary) 28%, transparent); outline-offset: 3px; }
.assistant-launcher svg { width: 19px; height: 19px; fill: none; stroke: currentColor; stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
.assistant-launcher[aria-expanded="true"] { min-width: 48px; width: 48px; padding: 0; font-size: 24px; }
.assistant-panel { position: fixed; right: 24px; bottom: 84px; width: min(400px, calc(100vw - 48px)); height: min(610px, calc(100vh - 112px)); border: 1px solid var(--line); border-radius: 16px; overflow: hidden; background: var(--panel); color: var(--ink); box-shadow: 0 22px 65px rgba(8, 28, 40, .24); display: grid; grid-template-rows: auto minmax(0, 1fr) auto auto auto auto; }
.assistant-header { min-width: 0; padding: 14px 14px 13px; border-bottom: 1px solid var(--line); background: linear-gradient(135deg, var(--primary-soft), var(--panel)); display: grid; grid-template-columns: 38px minmax(0, 1fr) auto 30px; align-items: center; gap: 9px; }
.assistant-mark { width: 36px; height: 36px; border-radius: 12px; background: var(--primary); color: #fff; display: grid; place-items: center; }
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
.assistant-bubble { padding: 11px 13px; border: 1px solid var(--line); border-radius: 5px 13px 13px 13px; background: var(--surface-soft); font-size: 12px; line-height: 1.7; }
.assistant-message.user .assistant-bubble { border-color: transparent; border-radius: 13px 5px 13px 13px; background: var(--primary); color: #fff; }
.assistant-bubble p { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
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
.assistant-composer { margin: 10px 14px 0; padding: 5px 5px 5px 11px; border: 1px solid var(--line); border-radius: 12px; background: var(--panel); display: grid; grid-template-columns: minmax(0, 1fr) 38px; align-items: end; gap: 6px; }
.assistant-composer:focus-within { border-color: var(--primary); box-shadow: 0 0 0 3px color-mix(in srgb, var(--primary) 12%, transparent); }
.assistant-composer textarea { min-height: 40px; max-height: 100px; padding: 6px 0; border: 0; outline: 0; resize: none; background: transparent; color: var(--ink); font: inherit; font-size: 12px; line-height: 1.5; }
.assistant-composer textarea::placeholder { color: var(--muted); }
.assistant-composer button { width: 36px; height: 36px; border: 0; border-radius: 10px; background: var(--primary); color: #fff; cursor: pointer; display: grid; place-items: center; }
.assistant-composer button:disabled { cursor: not-allowed; opacity: .45; }
.assistant-composer svg { width: 18px; height: 18px; fill: none; stroke: currentColor; stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
.assistant-panel footer { padding: 8px 14px 11px; color: var(--muted); font-size: 9px; text-align: center; }
@keyframes assistant-pulse { 0%, 70%, 100% { opacity: .25; transform: translateY(0); } 35% { opacity: 1; transform: translateY(-2px); } }
@media (max-width: 640px) {
  .assistant-launcher { right: 14px; bottom: 14px; min-width: 48px; width: 48px; padding: 0; }
  .assistant-launcher span:not([aria-hidden="true"]) { display: none; }
  .assistant-panel { right: 12px; bottom: 72px; width: calc(100vw - 24px); height: min(650px, calc(100dvh - 88px)); }
}
@media (prefers-reduced-motion: reduce) {
  .assistant-launcher { transition: none; }
  .assistant-thinking i { animation: none; opacity: .7; }
}
</style>

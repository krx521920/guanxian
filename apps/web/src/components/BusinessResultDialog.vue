<script setup lang="ts">
import { onMounted, onUnmounted, nextTick, ref } from 'vue'
const props = defineProps<{ title: string; returnFocus?: HTMLElement | null }>()
const emit = defineEmits<{ close: [] }>()
const panel = ref<HTMLElement | null>(null)
const previous = props.returnFocus || document.activeElement as HTMLElement | null
onMounted(() => panel.value?.querySelector<HTMLButtonElement>('button')?.focus())
onUnmounted(() => { void nextTick(() => { if (previous?.isConnected) previous.focus() }) })
function trap(event: KeyboardEvent) {
  const buttons = [...(panel.value?.querySelectorAll<HTMLElement>('button:not(:disabled), a[href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex="0"]') || [])]
  const first = buttons[0], last = buttons[buttons.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
</script>
<template>
  <Teleport to="body"><div class="business-dialog-backdrop" @click.self="emit('close')">
    <section ref="panel" class="business-dialog" role="dialog" aria-modal="true" :aria-label="title" @keydown.esc.stop.prevent="emit('close')" @keydown.tab="trap">
      <header><h2>{{ title }}</h2><button type="button" aria-label="关闭业务结果" @click="emit('close')">关闭 ×</button></header>
      <slot />
    </section>
  </div></Teleport>
</template>
<style scoped>
.business-dialog-backdrop{position:fixed;inset:0;background:#14263a66;display:grid;place-items:center;padding:24px;z-index:1400}
.business-dialog{background:white;color:#192e45;border-radius:18px;box-shadow:0 24px 90px #14263a40;width:min(1040px,100%);max-height:88dvh;overflow:auto;padding:24px;line-height:1.7}
header{display:flex;align-items:center;justify-content:space-between;gap:20px;margin-bottom:20px}h2{font-size:22px;margin:0;overflow-wrap:anywhere}button{padding:8px 14px;border:1px solid #ced9e5;background:#fff;color:#234467;border-radius:9px;cursor:pointer;white-space:nowrap}
header{position:sticky;top:0;background:#fff;z-index:2;padding:8px 0}
header::before{content:"";position:absolute;inset:-24px 0 0;background:#fff;z-index:-1;pointer-events:none}
@media(max-width:600px){.business-dialog-backdrop{padding:0}.business-dialog{height:100dvh;max-height:100dvh;border-radius:0;padding:18px}}
</style>

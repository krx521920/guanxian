<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  page: number
  size: number
  total: number
  disabled?: boolean
}>(), { disabled: false })

const emit = defineEmits<{
  change: [page: number]
  resize: [size: number]
}>()

const pageCount = computed(() => Math.max(1, Math.ceil(props.total / props.size)))
const canNext = computed(() => props.page + 1 < pageCount.value)
const start = computed(() => props.total === 0 ? 0 : props.page * props.size + 1)
const end = computed(() => Math.min(props.total, (props.page + 1) * props.size))
</script>

<template>
  <nav class="pagination-bar" aria-label="分页">
    <span>第 {{ start }}–{{ end }} 条，共 {{ total }} 条</span>
    <label>
      每页
      <select :value="size" :disabled="disabled" @change="emit('resize', Number(($event.target as HTMLSelectElement).value))">
        <option :value="10">10</option><option :value="20">20</option><option :value="50">50</option><option :value="100">100</option>
      </select>
    </label>
    <button type="button" class="secondary-button small" :disabled="disabled || page <= 0" @click="emit('change', page - 1)">上一页</button>
    <strong>{{ page + 1 }} / {{ pageCount }}</strong>
    <button type="button" class="secondary-button small" :disabled="disabled || !canNext" @click="emit('change', page + 1)">下一页</button>
  </nav>
</template>

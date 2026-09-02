<script setup lang="ts">
import { computed } from 'vue'
import { displayStatus } from './status-display'

const props = defineProps<{ value: string | null | undefined }>()
const label = computed(() => displayStatus(props.value))

const toneMap: Record<string, string> = {
  已认证: 'success',
  现行有效: 'success',
  已完成: 'success',
  已达成: 'success',
  已确认: 'success',
  已关闭: 'neutral',
  待完善: 'warning',
  即将施行: 'warning',
  沟通中: 'warning',
  待审核: 'info',
  待确认: 'info',
  待受理: 'info',
  已推荐: 'info',
  征求意见: 'neutral',
  方案沟通: 'warning',
  联合评估: 'warning',
  高: 'danger',
  中: 'warning',
  低: 'neutral',
}
</script>

<template>
  <span class="status-badge" :class="`status-${toneMap[label] || 'neutral'}`">{{ label }}</span>
</template>

<script setup lang="ts">
const props = defineProps<{ value: string }>()

const toneMap: Record<string, string> = {
  已认证: 'success',
  现行有效: 'success',
  已完成: 'success',
  已达成: 'success',
  正在招标: 'success',
  协会已推送: 'success',
  待完善: 'warning',
  即将施行: 'warning',
  沟通中: 'warning',
  待审核: 'info',
  待确认: 'info',
  待受理: 'info',
  已推荐: 'info',
  征求意见: 'neutral',
  已截止: 'neutral',
  方案沟通: 'warning',
  联合评估: 'warning',
  高: 'danger',
  中: 'warning',
  低: 'neutral',
}
</script>

<template>
  <span class="status-badge" :class="`status-${toneMap[props.value] || 'neutral'}`">{{ value }}</span>
</template>

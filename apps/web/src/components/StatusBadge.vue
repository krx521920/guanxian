<script setup lang="ts">
const props = defineProps<{ value: string }>()

const toneMap: Record<string, string> = {
  已认证: 'success',
  现行有效: 'success',
  已完成: 'success',
  已达成: 'success',
  已发布: 'success',
  双方已确认: 'success',
  企业已确认: 'success',
  需求方已确认: 'success',
  供给方已确认: 'success',
  协会推荐: 'info',
  已推荐: 'info',
  待完善: 'warning',
  即将施行: 'warning',
  沟通中: 'warning',
  待协会推荐: 'warning',
  待企业确认: 'warning',
  待补充材料: 'warning',
  待处理: 'warning',
  已退回: 'warning',
  洽谈中: 'warning',
  成果待归档: 'warning',
  一方已确认: 'warning',
  待审核: 'info',
  待确认: 'info',
  待受理: 'info',
  待双方确认: 'info',
  进行中: 'info',
  已开放: 'info',
  已邀请: 'info',
  征求意见: 'neutral',
  方案沟通: 'warning',
  联合评估: 'warning',
  高: 'danger',
  逾期: 'danger',
  中: 'warning',
  低: 'neutral',
  草稿: 'neutral',
  已停用: 'neutral',
  已删除: 'neutral',
  已关闭: 'neutral',
  已归档: 'neutral',
  成果已归档: 'neutral',
  洽谈终止: 'neutral',
}
</script>

<template>
  <span class="status-badge" :class="`status-${toneMap[props.value] || 'neutral'}`">{{ value }}</span>
</template>

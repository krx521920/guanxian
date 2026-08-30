<script setup lang="ts">
import { computed } from 'vue'
import {
  Building2,
  ClipboardCheck,
  FileCheck2,
  GitCompareArrows,
  PackageCheck,
  ScrollText,
  Sparkles,
  Wrench,
} from '@lucide/vue'
import type { Metric } from '../types/domain'

const props = defineProps<{ metric: Metric; icon?: string }>()

const iconComponents = {
  '企': Building2,
  '档': FileCheck2,
  '联': GitCompareArrows,
  '待': ClipboardCheck,
  '品': PackageCheck,
  '机': Sparkles,
  '协': Wrench,
  '策': ScrollText,
}
const iconComponent = computed(() => iconComponents[props.icon as keyof typeof iconComponents] || Sparkles)
const sparkHeights = computed(() => ({
  info: [34, 48, 42, 61, 57, 78],
  success: [30, 39, 52, 49, 68, 82],
  warning: [56, 41, 62, 47, 71, 65],
  danger: [68, 60, 72, 58, 51, 44],
  neutral: [40, 48, 44, 53, 51, 60],
}[props.metric.tone]))
</script>

<template>
  <article class="metric-card" :class="`tone-${metric.tone}`">
    <div class="metric-top">
      <span class="metric-label">{{ metric.label }}</span>
      <span class="metric-symbol"><component :is="iconComponent" aria-hidden="true" /></span>
    </div>
    <div class="metric-value-row">
      <div><strong>{{ metric.value }}</strong><p>{{ metric.change }}</p></div>
      <span class="metric-sparkline" aria-hidden="true"><i v-for="(height, index) in sparkHeights" :key="index" :style="{ height: `${height}%` }" /></span>
    </div>
  </article>
</template>

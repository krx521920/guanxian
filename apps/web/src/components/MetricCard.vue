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
</script>

<template>
  <article class="metric-card" :class="`tone-${metric.tone}`">
    <div class="metric-top">
      <span class="metric-label">{{ metric.label }}</span>
      <span class="metric-symbol"><component :is="iconComponent" aria-hidden="true" /></span>
    </div>
    <strong>{{ metric.value }}</strong>
    <p>{{ metric.change }}</p>
  </article>
</template>

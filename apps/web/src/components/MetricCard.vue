<script setup lang="ts">
import type { Metric } from '../types/domain'

defineProps<{ metric: Metric; icon?: string }>()
</script>

<template>
  <article class="metric-card" :class="`tone-${metric.tone}`">
    <div class="metric-top">
      <span class="metric-label">{{ metric.label }}</span>
      <span class="metric-symbol">{{ icon || '↗' }}</span>
    </div>
    <strong>{{ metric.value }}</strong>
    <p>{{ metric.change }}</p>
  </article>
</template>

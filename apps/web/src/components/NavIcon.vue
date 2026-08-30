<script setup lang="ts">
import {
  Building2,
  ClipboardCheck,
  GitCompareArrows,
  LayoutDashboard,
  Network,
  ScrollText,
} from '@lucide/vue'
import type { NavIcon } from '../config/navigation'

defineProps<{ name: NavIcon }>()
</script>

<template>
  <LayoutDashboard v-if="name === 'dashboard'" aria-hidden="true" />
  <Network v-else-if="name === 'ecosystem'" aria-hidden="true" />
  <Building2 v-else-if="name === 'enterprise'" aria-hidden="true" />
  <ScrollText v-else-if="name === 'policy'" aria-hidden="true" />
  <GitCompareArrows v-else-if="name === 'match'" aria-hidden="true" />
  <ClipboardCheck v-else aria-hidden="true" />
</template>

<style scoped>
svg { width: 19px; height: 19px; stroke-width: 1.8; }
</style>

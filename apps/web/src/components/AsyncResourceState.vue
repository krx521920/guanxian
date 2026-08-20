<script setup lang="ts">
import type { PageResourceError } from '../composables/useAsyncResource'
import LoadingBlock from './LoadingBlock.vue'

defineProps<{
  loading: boolean
  error: PageResourceError | null
}>()

defineEmits<{
  retry: []
}>()
</script>

<template>
  <LoadingBlock v-if="loading" />
  <section v-else-if="error" class="resource-error panel" role="alert" aria-live="polite">
    <span class="resource-error-icon" aria-hidden="true">!</span>
    <h2>内容暂时无法加载</h2>
    <p>{{ error.message }}</p>
    <small v-if="error.requestId">请求编号：<code>{{ error.requestId }}</code></small>
    <button class="primary-button" type="button" @click="$emit('retry')">重新加载</button>
  </section>
</template>

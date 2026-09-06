<script setup lang="ts">
import { computed, defineAsyncComponent } from 'vue'
import { useRoute } from 'vue-router'
import { useAuth } from '../services/auth'
const route = useRoute(), auth = useAuth()
const projects = computed(() => route.query.view === 'projects')
const MatchingView = defineAsyncComponent(() => import('./MatchingView.vue'))
const CollaborationsView = defineAsyncComponent(() => import('./CollaborationsView.vue'))
</script>
<template>
  <div>
    <section class="panel cooperation-context" aria-label="我的合作">
      <div><h1>我的合作</h1><p>{{ auth.user.value?.organization }} · 只展示本企业权限范围内的合作记录。{{ auth.user.value?.role === 'ENTERPRISE_ADMIN' ? '确认邀请、记录洽谈与推进协作前，请核对合作对象和操作内容。' : '普通成员可查看进度，不能代表企业确认或变更合作。' }}</p></div>
      <nav aria-label="合作阶段"><RouterLink :class="['secondary-button', { selected: !projects }]" :aria-current="!projects ? 'page' : undefined" to="/enterprise/cooperation">合作机会与邀请</RouterLink><RouterLink :class="['secondary-button', { selected: projects }]" :aria-current="projects ? 'page' : undefined" to="/enterprise/cooperation?view=projects">协作进展</RouterLink></nav>
    </section>
    <CollaborationsView v-if="projects" /><MatchingView v-else />
  </div>
</template>
<style scoped>
.cooperation-context{padding:22px;margin-bottom:24px}.cooperation-context h1{font-size:25px;margin:0 0 8px}.cooperation-context p{color:var(--muted);line-height:1.8;margin:0 0 18px}.cooperation-context nav{display:flex;gap:12px;flex-wrap:wrap}.selected{background:var(--primary,#28726b);color:white}
</style>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import AsyncResourceState from '../components/AsyncResourceState.vue'
import PageHeader from '../components/PageHeader.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { useAsyncResource } from '../composables/useAsyncResource'
import { platformApi } from '../services/platform-api'

const { data: items, loading, error, load } = useAsyncResource(platformApi.members)
const keyword = ref('')
const status = ref('全部')
const filtered = computed(() => (items.value || []).filter((item) => {
  const hitsKeyword = !keyword.value || `${item.name}${item.role}${item.products.join('')}`.includes(keyword.value)
  return hitsKeyword && (status.value === '全部' || item.status === status.value)
}))

onMounted(load)
</script>

<template>
  <div>
    <PageHeader eyebrow="MEMBER ENTERPRISES" title="会员企业" description="统一沉淀会员画像、产品服务与场景能力">
      <button class="secondary-button">下载调查模板</button><button class="secondary-button">批量导入</button><button class="primary-button">+ 新增企业</button>
    </PageHeader>
    <section class="panel filter-panel">
      <div class="search-box"><span>⌕</span><input v-model="keyword" placeholder="搜索企业名称、业务角色或产品服务" /></div>
      <select v-model="status" class="filter-select"><option>全部</option><option>已认证</option><option>待完善</option><option>待审核</option></select>
      <span class="result-count">共 {{ filtered.length }} 家企业</span>
    </section>
    <AsyncResourceState v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <section v-else-if="items" class="panel flush-panel">
      <div class="data-table-wrap">
        <table class="data-table member-table">
          <thead><tr><th>企业</th><th>业务角色 / 场景</th><th>主要产品与服务</th><th>资料完整度</th><th>状态</th><th>更新日期</th><th></th></tr></thead>
          <tbody>
            <tr v-for="item in filtered" :key="item.id">
              <td><div class="enterprise-cell"><span class="enterprise-logo">{{ item.shortName.slice(0, 2) }}</span><div><strong>{{ item.name }}</strong><small>{{ item.city }} · 联系人：{{ item.contact }}</small></div></div></td>
              <td><span class="table-muted">{{ item.role }}</span><div class="tags"><span v-for="scene in item.scenes" :key="scene">{{ scene }}</span></div></td>
              <td>{{ item.products.join('、') }}</td>
              <td><div class="completion-cell"><div class="progress-track"><i :style="{ width: `${item.completeness}%` }" /></div><strong>{{ item.completeness }}%</strong></div></td>
              <td><StatusBadge :value="item.status" /></td><td class="table-muted">{{ item.updatedAt }}</td><td><RouterLink class="row-action" :to="`/members/${item.id}/edit`">编辑</RouterLink></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>

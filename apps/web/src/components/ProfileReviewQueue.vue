<script setup lang="ts">
import { ref } from 'vue'
import { request } from '../services/http'
const open=ref(false),busy=ref(false),error=ref(''),page=ref(0)
const items=ref<{id:string;name:string;submittedAt:string}[]>([])
async function load(target=0){
  if(busy.value)return
  open.value=true;busy.value=true;error.value=''
  try{items.value=await request(`/enterprise-profile-reviews?page=${target}`,{cache:'no-store'});page.value=target}
  catch(e){items.value=[];error.value=e instanceof Error?e.message:'审核队列读取失败'}
  finally{busy.value=false}
}
</script>
<template><section class="profile-review-queue"><button class="secondary-button" :disabled="busy" @click="load()">查看资料待审核队列</button><div v-if="open" class="panel"><h3>资料草稿待审核</h3><p>此队列与会员入库审核分开，提交草稿不会改变企业可用状态。</p><p v-if="error" role="alert">{{ error }}</p><p v-else-if="!items.length">当前页暂无待审核资料。</p><ul><li v-for="item in items" :key="item.id"><RouterLink :to="`/members/${item.id}/edit`">{{ item.name }} · 核对草稿与差异 →</RouterLink></li></ul><div class="queue-actions"><button class="secondary-button" :disabled="busy || page === 0" @click="load(page-1)">上一页</button><span>第 {{ page+1 }} 页</span><button class="secondary-button" :disabled="busy || items.length !== 20" @click="load(page+1)">下一页</button><button class="text-button" @click="open=false">收起队列</button></div></div></section></template>
<style scoped>.profile-review-queue{margin:16px 0}.profile-review-queue .panel{padding:20px;margin-top:12px}.profile-review-queue li{margin:14px 0}.queue-actions{display:flex;gap:12px;flex-wrap:wrap;align-items:center}.profile-review-queue p{line-height:1.8}</style>

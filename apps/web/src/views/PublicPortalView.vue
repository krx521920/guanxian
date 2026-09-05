<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { onMounted, ref } from 'vue'
import { publicEnterprises, displayField, type PublicEnterprise } from '../services/profile-workflow'
const items = ref<PublicEnterprise[]>([]), selected = ref<PublicEnterprise | null>(null)
const query = ref(''), page = ref(0), busy = ref(false), error = ref('')
async function load(target = 0) {
  if (busy.value) return
  busy.value = true; error.value = ''; selected.value = null
  try { items.value = await publicEnterprises(query.value, target); page.value = target }
  catch (e) { items.value = []; error.value = e instanceof Error ? e.message : '读取失败' }
  finally { busy.value = false }
}
onMounted(() => load())
</script>

<template>
  <div class="public-portal">
    <a class="public-skip" href="#public-main">跳到主要内容</a>
    <header class="public-header">
      <RouterLink to="/public" class="public-brand" aria-label="管线智联公开平台首页">
        <div class="brand-mark"><span /><span /><span /></div>
        <span><strong>管线智联</strong><small>行业协作 · 公开平台</small></span>
      </RouterLink>
      <nav aria-label="公开平台导航">
        <a href="#public-directory">企业展示</a><a href="#public-guide">使用说明</a>
        <RouterLink class="public-login" to="/login">登录工作空间 →</RouterLink>
      </nav>
    </header>
    <main id="public-main">
      <section class="public-hero">
        <div class="public-container">
          <span class="public-eyebrow">开放浏览 / 无需登录</span>
          <h1>连接行业信息<br />让协作从了解开始</h1>
          <p>浏览经过独立审核、获得企业明确授权的公开资料。企业管理和协会运营请进入各自的授权工作空间。</p>
          <a class="public-hero-action" href="#public-directory">查看企业公开展示 <span aria-hidden="true">↓</span></a>
          <div class="public-hero-note">当前为游客只读页面，不提供内部资料和管理操作。</div>
        </div>
      </section>
      <div class="public-container public-content">
        <section id="public-directory" class="public-directory" aria-labelledby="directory-title">
          <div class="public-section-heading"><div><span class="public-eyebrow">企业展示</span><h2 id="directory-title">企业公开目录</h2></div><span class="public-stage">仅展示已授权发布版本</span></div>
          <form class="public-search" @submit.prevent="load()"><label>企业名称或业务类别<input v-model="query" maxlength="100" /></label><button class="secondary-button" :disabled="busy">搜索公开企业</button></form>
          <p v-if="error" role="alert">{{ error }}</p><p v-if="busy" role="status">正在读取公开目录…</p>
          <div v-if="!busy && !error && !items.length" class="public-empty">
            <span class="public-empty-icon" aria-hidden="true">企</span>
            <h3>暂无符合条件的已发布企业</h3>
            <p>这里只展示独立审核、获得企业授权并由管理员发布的版本。草稿、待审内容和仅导入的企业不会出现在这里。</p>
            <small>这不代表平台没有会员企业。已绑定的企业用户可以登录查看本企业资料。</small>
            <RouterLink to="/login?entry=enterprise" class="public-inline-link">我是企业用户，前往登录 →</RouterLink>
          </div>
          <div class="public-cards"><article v-for="item in items" :key="item.id"><h3>{{ item.name }}</h3><span>{{ item.category }} · 资料已审核</span><p>{{ item.introduction || '暂无简介' }}</p><button class="secondary-button" @click="selected = selected?.id === item.id ? null : item">{{ selected?.id === item.id ? '收起详情' : '查看公开详情' }}</button><dl v-if="selected?.id === item.id"><template v-for="[key,label] in ([['capabilities','技术能力'],['products','产品'],['services','服务'],['applicationScenarios','应用场景']] as const)" :key="key"><dt>{{ label }}</dt><dd>{{ displayField(item[key]) }}</dd></template><dt>公开版本发布时间</dt><dd>{{ new Date(item.publishedAt).toLocaleString() }}</dd></dl></article></div>
          <div class="public-search"><button class="secondary-button" :disabled="busy || page === 0" @click="load(page - 1)">上一页</button><span>第 {{ page + 1 }} 页</span><button class="secondary-button" :disabled="busy || items.length !== 20" @click="load(page + 1)">下一页</button></div>
        </section>
        <section id="public-guide" aria-labelledby="guide-title">
          <div class="public-section-heading"><div><span class="public-eyebrow">使用说明</span><h2 id="guide-title">找到适合您的工作空间</h2></div></div>
          <div class="public-guide-grid">
            <article><span class="public-step">01 / 游客</span><h3>了解平台</h3><p>无需账号即可检索企业公开目录，查看已发布版本。待审核资料、私人联系方式与内部附件不向游客开放。</p><a href="#public-directory">浏览公开企业 →</a></article>
            <article><span class="public-step">02 / 企业</span><h3>进入企业工作台</h3><p>使用已由协会核验并绑定企业的账号，查看本企业资料、政策及合作进度。管理操作按账号授权显示。</p><RouterLink to="/login?entry=enterprise">企业登录 →</RouterLink></article>
            <article><span class="public-step">03 / 管理人员</span><h3>进入管理后台</h3><p>协会管理员和运营人员登录后，按职责处理会员资料、审核与协作事项。入口选择不会授予权限。</p><RouterLink to="/login?entry=admin">管理员登录 →</RouterLink></article>
          </div>
        </section>
        <aside class="public-privacy"><strong>公开展示，不等于开放内部数据</strong><p>游客不能编辑资料、下载内部附件或查询私人联系方式。登录身份与企业绑定由后台核验；如需开通企业账号，请联系协会管理员。</p></aside>
      </div>
    </main>
    <footer class="public-footer"><span>管线智联 · 行业管理协作平台</span><RouterLink to="/login">返回统一入口</RouterLink></footer>
  </div>
</template>

<style scoped>
.public-search{display:flex;gap:16px;align-items:center;flex-wrap:wrap;margin:20px 0}.public-search label{display:grid;gap:8px}.public-search input{font:inherit;padding:10px;border:1px solid #c8d3df;border-radius:8px;max-width:100%}.public-cards{display:grid;gap:20px}.public-cards article{background:white;padding:24px;border:1px solid #dce4ed;border-radius:12px;overflow-wrap:anywhere}.public-cards p,.public-cards dd{white-space:pre-wrap;line-height:1.8}.public-cards dt{font-weight:600;margin-top:12px}.public-cards dd{margin-left:0}
.public-portal { min-height: 100vh; color: #1e3046; background: #f5f7fa; }
.public-portal a { text-decoration: none; }
.public-portal a:focus-visible { outline: 3px solid #5b95d1; outline-offset: 5px; }
.public-skip { position: absolute; left: 20px; top: -80px; z-index: 10; padding: 12px; background: white; }
.public-skip:focus { top: 12px; }
.public-header { max-width: 1280px; margin: auto; padding: 24px 40px; display: flex; align-items: center; justify-content: space-between; gap: 24px; }
.public-brand { display: flex; gap: 12px; align-items: center; color: #1e3046; }
.public-brand > span { display: grid; gap: 4px; }
.public-brand strong { font-size: 21px; letter-spacing: .1em; }
.public-brand small { color: #64748b; font-size: 12px; }
.public-header nav { display: flex; align-items: center; gap: 26px; font-size: 14px; }
.public-header nav a { color: #42566d; }
.public-header nav .public-login { padding: 12px 18px; color: white; background: #1a4d8f; border-radius: 8px; }
.public-container { max-width: 1200px; margin: auto; }
.public-hero { padding: 64px 40px; color: white; background: radial-gradient(ellipse at 90% 70%, #24496e 0, #152e50 48%, #122743 100%); }
.public-eyebrow { display: inline-block; font-size: 12px; font-weight: 600; letter-spacing: .14em; color: #526a85; }
.public-hero .public-eyebrow { color: #b9d2ea; }
.public-hero h1 { margin: 20px 0; font-size: clamp(32px, 4vw, 52px); line-height: 1.35; letter-spacing: -.02em; }
.public-hero p { max-width: 600px; color: #c5d3e3; font-size: 16px; line-height: 1.9; }
.public-hero-action { display: inline-flex; gap: 28px; margin: 20px 0; padding: 14px 20px; color: #1a3f6d; background: #fff; border-radius: 8px; font-weight: 600; font-size: 14px; }
.public-hero-note { color: #bbcbdd; font-size: 12px; }
.public-content { padding: 44px 40px; display: grid; gap: 40px; }
.public-section-heading { display: flex; align-items: center; justify-content: space-between; gap: 18px; margin-bottom: 20px; }
.public-section-heading h2 { margin: 8px 0 0; font-size: 25px; }
.public-stage { padding: 7px 11px; border: 1px solid #d5e0eb; border-radius: 6px; color: #526a85; font-size: 12px; white-space: nowrap; }
.public-empty { padding: 38px 24px; text-align: center; background: white; border: 1px solid #dde5ee; border-radius: 14px; }
.public-empty-icon { display: grid; place-items: center; width: 52px; height: 52px; margin: auto; background: #edf3fa; color: #356493; font-size: 22px; border-radius: 14px; }
.public-empty h3 { font-size: 20px; margin: 18px 0 12px; }
.public-empty p { max-width: 670px; margin: auto; color: #566a80; font-size: 14px; line-height: 1.9; }
.public-empty small { display: block; margin-top: 12px; color: #617389; line-height: 1.8; }
.public-inline-link { display: inline-block; margin-top: 24px; color: #1a4d8f; font-size: 14px; font-weight: 600; }
.public-guide-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 18px; }
.public-guide-grid article { padding: 24px; background: white; border: 1px solid #dde5ee; border-radius: 12px; display: flex; flex-direction: column; }
.public-step { color: #567795; font-size: 12px; letter-spacing: .06em; }
.public-guide-grid h3 { margin: 14px 0 8px; font-size: 18px; }
.public-guide-grid p { flex: 1; color: #5e7187; font-size: 14px; line-height: 1.85; margin: 0 0 22px; }
.public-guide-grid a { color: #1a4d8f; font-size: 14px; font-weight: 600; }
.public-privacy { border-left: 3px solid #93aec9; padding: 6px 20px; }
.public-privacy strong { font-size: 14px; }.public-privacy p { margin: 10px 0 0; color: #5e7187; font-size: 13px; line-height: 1.9; }
.public-footer { max-width: 1200px; margin: auto; padding: 24px 40px; border-top: 1px solid #dde5ee; color: #64748b; font-size: 12px; display: flex; justify-content: space-between; gap: 16px; }
.public-footer a { color: #345d89; }
@media (max-width: 720px) { .public-header { padding: 20px; flex-wrap: wrap; }.public-header nav { width: 100%; gap: 18px; font-size: 13px; flex-wrap: wrap; }.public-hero { padding: 40px 24px; }.public-content { padding: 32px 20px; }.public-guide-grid { grid-template-columns: 1fr; }.public-footer { padding: 24px 20px; flex-wrap: wrap; }.public-section-heading { flex-wrap: wrap; } }
</style>

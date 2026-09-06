<script setup lang="ts">
import LoginCard from './LoginCard.vue'
</script>

<template>
  <main class="lp-page">
    <div class="lp-grid" aria-hidden="true" />
    <svg class="lp-pipes" viewBox="0 0 1440 200" fill="none" preserveAspectRatio="xMidYMax slice" aria-hidden="true">
      <path d="M-10 44 H480 L540 100 H1450" />
      <path d="M-10 100 H300 L360 156 H1450" />
      <path d="M-10 156 H700 L760 100 H980 L1040 156 H1450" />
      <circle cx="540" cy="100" r="3.5" />
      <circle cx="360" cy="156" r="3.5" />
      <circle cx="760" cy="100" r="3.5" />
      <circle cx="1040" cy="156" r="3.5" />
      <circle cx="180" cy="44" r="2.5" />
      <circle cx="1180" cy="156" r="2.5" />
    </svg>

    <div class="lp-column">
      <header class="lp-identity">
        <div class="brand-mark"><span /><span /><span /></div>
        <div>
          <strong>北京地下管线协会</strong>
          <span>管线智联 · 管理协作平台<em class="official-badge on-light">官方平台</em></span>
        </div>
      </header>

      <LoginCard />

      <div class="lp-status">
        <span><i class="status-dot lg-pulse" aria-hidden="true" />系统运行正常</span>
        <i class="lp-status-sep" aria-hidden="true" />
        <span>数据均标注来源与更新时间 · 登录后可见各模块实时数据</span>
      </div>

      <footer class="lp-footer">
        主办单位：北京地下管线协会 · 运维责任主体：协会系统管理员<br />
        本系统操作留痕、数据可追溯
      </footer>
    </div>
  </main>
</template>

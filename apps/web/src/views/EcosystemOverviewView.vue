<script setup lang="ts">
import { computed, ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'

const sectors = [
  { name: '水', count: 18, note: '供水与再生水' },
  { name: '电', count: 12, note: '电力与综合管廊' },
  { name: '气', count: 21, note: '燃气安全运行' },
  { name: '热', count: 15, note: '供热与节能改造' },
  { name: '排', count: 17, note: '排水与防涝治理' },
]

const memberPools = [
  { label: '设备供应商', value: 42, tone: 'teal' },
  { label: '工程服务商', value: 31, tone: 'blue' },
  { label: '技术与设计', value: 21, tone: 'amber' },
  { label: '科研及其他', value: 12, tone: 'violet' },
]

const trialAreas = [
  { name: '回龙观', units: 37, grids: 286, claimed: 92, alerts: 4, streets: ['龙泽园', '回龙观', '霍营'] },
  { name: '天通苑', units: 31, grids: 248, claimed: 88, alerts: 7, streets: ['天通苑北', '天通苑南', '太平庄'] },
]

const selectedArea = ref(trialAreas[0])
const selectedCell = ref(18)
const gridCells = Array.from({ length: 70 }, (_, index) => ({
  id: index,
  level: [6, 18, 19, 33, 46, 47, 61].includes(index) ? 'hot' : [5, 17, 20, 32, 34, 45, 48, 60, 62].includes(index) ? 'warm' : 'normal',
}))

const activeGrid = computed(() => ({
  name: `${selectedArea.value.name} · HT-${String(selectedCell.value + 1).padStart(3, '0')}`,
  services: selectedCell.value % 3 === 0 ? ['燃气', '排水', '供热'] : ['供水', '电力', '燃气'],
  units: selectedCell.value % 3 === 0 ? 7 : 5,
  activity: selectedCell.value % 3 === 0 ? '高' : '中',
}))

const tiers = [
  { name: '副理事长单位', level: '战略共建', accent: '最高权限', features: ['查看全平台生态池', '跨区域需求对接', '行业报告与政策洞察', '优先参与联合项目'] },
  { name: '理事单位', level: '协同连接', accent: '扩展权限', features: ['查看授权会员能力', '发布供需与产品', 'AI 生成定向宣传册', '参与专业部门协作'] },
  { name: '普通会员单位', level: '基础服务', accent: '本域权限', features: ['管理本企业资料', '查看公开政策信息', '接收匹配与合规提示', '使用一键海报工具'] },
]

</script>

<template>
  <div class="ecosystem-page">
    <PageHeader eyebrow="AI ECOSYSTEM COMMAND CENTER" title="地下管线 AI 生态全景" description="连接政府生态位、会员能力与社区网格，让政策、产品和需求形成可持续协作闭环">
      <RouterLink class="primary-button ecosystem-link-button" to="/matching">进入智能匹配 →</RouterLink>
    </PageHeader>

    <section class="ecosystem-hero panel">
      <div class="ecosystem-water-copy">
        <span class="ecosystem-kicker">需求 / 监管侧</span>
        <h2>城市服务生态位</h2>
        <p>承接政策要求与城市治理需求，形成面向行业的服务采购与监管协同入口。</p>
      </div>
      <div class="sector-row">
        <article v-for="sector in sectors" :key="sector.name" class="sector-node">
          <span>{{ sector.name }}</span><div><strong>{{ sector.count }}</strong><small>家需求单位</small></div><p>{{ sector.note }}</p>
        </article>
      </div>

      <div class="waterline"><span>政策 · 标准 · 需求</span><i /><div class="ai-orbit"><b>AI</b><small>生态中枢</small></div><i /><span>产品 · 能力 · 服务</span></div>

      <div class="ecosystem-water-copy underwater-copy">
        <span class="ecosystem-kicker">会员 / 服务侧</span>
        <h2>106 家会员能力池</h2>
        <p>企业拥有独立数字名片与产品 IP，由 AI 持续识别上下游关系、政策影响及合作机会。</p>
      </div>
      <div class="member-pool-row">
        <article v-for="pool in memberPools" :key="pool.label" class="member-pool" :class="pool.tone">
          <span>{{ pool.label }}</span><strong>{{ pool.value }}</strong><small>家</small><i :style="{ width: `${pool.value * 2}%` }" />
        </article>
      </div>
      <div class="ecosystem-flow">
        <span>企业资料上传</span><b>→</b><span>AI 结构化诊断</span><b>→</b><span>政策关联筛查</span><b>→</b><span>供需智能匹配</span><b>→</b><span>人工确认发布</span>
      </div>
    </section>

    <section class="ecosystem-section-heading">
      <div><span class="eyebrow">HUITIAN PILOT</span><h2>回天地区网格化试点</h2><p>仅展示社区级服务覆盖，不呈现涉密管线位置</p></div>
      <div class="pilot-tabs"><button v-for="area in trialAreas" :key="area.name" :class="{ active: selectedArea.name === area.name }" @click="selectedArea = area">{{ area.name }}</button></div>
    </section>

    <section class="pilot-grid-layout">
      <article class="matrix-panel panel">
        <div class="panel-header"><div><h2>管线矩阵 Matrix</h2><p>点击网格查看街道级服务覆盖与活跃度</p></div><span class="live-badge"><i /> LIVE</span></div>
        <div class="matrix-content">
          <div class="matrix-map">
            <button v-for="cell in gridCells" :key="cell.id" :class="['matrix-cell', cell.level, { selected: selectedCell === cell.id }]" :aria-label="`网格 ${cell.id + 1}`" @click="selectedCell = cell.id"><i v-if="cell.level !== 'normal'" /></button>
          </div>
          <aside class="grid-detail">
            <span class="eyebrow">当前网格</span><h3>{{ activeGrid.name }}</h3>
            <dl><div><dt>服务单位</dt><dd>{{ activeGrid.units }} 家</dd></div><div><dt>覆盖专业</dt><dd>{{ activeGrid.services.length }} 类</dd></div><div><dt>区域活跃度</dt><dd class="activity-high">{{ activeGrid.activity }}</dd></div></dl>
            <div class="service-tags"><span v-for="service in activeGrid.services" :key="service">{{ service }}</span></div>
            <p>AI 提示：近期施工咨询与燃气安全查询增加，建议核验服务响应资源。</p>
          </aside>
        </div>
      </article>

      <aside class="pilot-summary">
        <article class="pilot-stat panel"><span>已接入单位</span><strong>{{ selectedArea.units }}</strong><small>辖区管线相关单位</small></article>
        <article class="pilot-stat panel"><span>试点网格</span><strong>{{ selectedArea.grids }}</strong><small>{{ selectedArea.streets.join(' · ') }}</small></article>
        <article class="pilot-stat panel"><span>服务认领率</span><strong>{{ selectedArea.claimed }}%</strong><div class="pilot-progress"><i :style="{ width: `${selectedArea.claimed}%` }" /></div></article>
        <article class="pilot-stat alert-stat panel"><span>待核验漏点</span><strong>{{ selectedArea.alerts }}</strong><small>需辖区对接人协同确认</small></article>
      </aside>
    </section>

    <section class="ecosystem-section-heading"><div><span class="eyebrow">MEMBER VALUE</span><h2>会员权益分级</h2><p>通过数据边界和增值能力体现协会服务价值</p></div><button class="secondary-button">查看权限矩阵</button></section>
    <section class="tier-grid">
      <article v-for="(tier, index) in tiers" :key="tier.name" class="tier-card panel" :class="{ featured: index === 0 }">
        <div class="tier-top"><span>0{{ index + 1 }}</span><em>{{ tier.accent }}</em></div><small>{{ tier.level }}</small><h3>{{ tier.name }}</h3>
        <ul><li v-for="feature in tier.features" :key="feature">✓ {{ feature }}</li></ul><button :class="index === 0 ? 'primary-button' : 'secondary-button'">查看权益详情</button>
      </article>
    </section>

  </div>
</template>

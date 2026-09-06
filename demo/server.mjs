// 管线智联 · 本地演示服务（零依赖 Node HTTP Server）
// ---------------------------------------------------------------------------
// 用途：在缺少 Java/Docker 的机器上，为真实 Vue 前端提供 /api/v1 演示接口。
// 数据来自 demo/data/*.json（会员/政策/招标种子），其余生态数据在启动时由会员派生。
// 这不是生产后端；Java 后端见 apps/server。前端在 demo 模式下会携带
// X-Guanxian-Demo-Role 请求头，本服务据此区分协会侧与企业侧身份。
// 启动：node demo/server.mjs   （默认 8080 端口，可通过环境变量 PORT 覆盖）
// ---------------------------------------------------------------------------
import http from 'node:http'
import { Readable } from 'node:stream'
import { readFileSync, existsSync, mkdirSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const ASSOCIATION_ID = '00000000-0000-0000-0000-000000000106'
const ENTERPRISE_ID = '00000000-0000-0000-0000-000000000201'
const PORT = Number(process.env.PORT || 8080)

const now = () => new Date().toISOString()
const uuid = (n) => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`
const day = (offset) => { const d = new Date(); d.setDate(d.getDate() + offset); return d.toISOString().slice(0, 10) }
const pad2 = (n) => String(n).padStart(2, '0')
const isoDate = (y, m, d, h = 9, min = 30) => `${y}-${pad2(m)}-${pad2(d)}T${pad2(h)}:${pad2(min)}:00+08:00`

// ---------------------------------------------------------------- 基础种子 ----
function loadJson(file, fallback) {
  const full = path.join(__dirname, 'data', file)
  if (!existsSync(full)) {
    console.warn(`[demo] 未找到 ${file}，使用内置最小种子（等 demo/data 生成后自动生效）`)
    return fallback
  }
  return JSON.parse(readFileSync(full, 'utf8'))
}

function fallbackMembers() {
  const rows = [
    ['京城管网科技有限公司', '管网监测', '昌平区', ['管网智能监测平台', '智能井盖传感器'], ['供水', '燃气', '热力', '排水'], 'ACTIVE', 92],
    ['北京瑞通管线探测有限公司', '管线探测', '海淀区', ['管线探测服务'], ['燃气', '排水'], 'ACTIVE', 88],
    ['北京恒源热力工程有限公司', '热力施工', '昌平区', ['热力管网改造施工'], ['热力'], 'ACTIVE', 85],
    ['北京安燃阀门制造有限公司', '阀门制造', '大兴区', ['智能阀门'], ['燃气', '供水'], 'ACTIVE', 80],
    ['北京清泓水务科技发展有限公司', '智慧水务', '朝阳区', ['智慧水务平台'], ['供水', '排水'], 'PENDING_REVIEW', 70],
    ['北京中勘测绘有限公司', '测绘服务', '丰台区', ['地下管线测绘'], ['通信', '电力'], 'ACTIVE', 75],
    ['北京鼎立非开挖工程有限公司', '非开挖修复', '通州区', ['非开挖管道修复'], ['供水', '排水'], 'ACTIVE', 78],
    ['北京铸诚管道材料有限公司', '管材供应', '房山区', ['PE管材', '球墨铸铁管'], ['供水', '燃气'], 'ACTIVE', 72],
    ['北京蓝焰检测技术有限公司', '检测监测', '朝阳区', ['燃气泄漏检测'], ['燃气'], 'INCOMPLETE', 45],
    ['北京永固井盖制造有限公司', '井盖制造', '门头沟区', ['智能井盖'], ['排水'], 'ACTIVE', 66],
  ]
  return rows.map((r, i) => {
    const id = i === 0 ? ENTERPRISE_ID : uuid(i + 1)
    return {
      id, associationId: ASSOCIATION_ID, name: r[0], shortName: r[0].slice(2, 6),
      unifiedSocialCreditCode: `911101${pad2(14)}MA01${String(1000000 + i * 7919).slice(0, 7)}`,
      category: '工程服务商', role: r[1], address: `北京市${r[2]}区示例街道1号`,
      district: r[2], contactName: '联系人', contactPhone: '13800000000',
      introduction: `${r[0]}，${r[1]}领域企业。`, capabilities: [r[1]],
      products: r[3], scenes: r[4], cooperationNeeds: [], visibility: 'ASSOCIATION',
      status: r[5], completeness: r[6], version: 1, createdAt: isoDate(2026, 5, 1 + i), updatedAt: isoDate(2026, 8, 20), deletedAt: null,
    }
  })
}

function fallbackPolicies() {
  const rows = [
    ['中华人民共和国安全生产法', '全国人民代表大会常务委员会', '国家', '法律', '2021-06-10', '2021-09-01', '生产经营单位必须落实安全生产主体责任，健全全员安全生产责任制；涉及燃气、热力等城市管网作业的单位应加强现场安全管理与隐患排查。', ['安全管理', '主体责任'], '中华人民共和国主席令第88号', 'PUBLISHED'],
    ['城镇燃气管理条例', '国务院', '国家', '行政法规', '2010-11-19', '2011-03-01', '规定燃气设施保护范围与第三方施工保护制度，施工单位在燃气设施保护范围内作业前应当查明地下燃气设施情况并采取安全保护措施。', ['燃气', '施工保护'], '国务院令第583号', 'PUBLISHED'],
    ['北京市地下管线管理办法', '北京市人民政府', '北京市', '地方规章', '2021-03-01', '2021-05-01', '要求管线单位建立地下管线档案资料动态更新机制，敷设、改建、废弃管线应当及时归档；新建道路建成后五年内不得重复开挖敷设管线。', ['档案管理', '道路开挖'], '北京市人民政府令第298号', 'PUBLISHED'],
    ['城镇排水与污水处理条例', '国务院', '国家', '行政法规', '2013-10-02', '2014-01-01', '要求排水户依法排入城镇排水设施，排水管网运行单位应定期检测维护，并建立管网运行档案。', ['排水', '运维'], '国务院令第641号', 'PUBLISHED'],
    ['北京市燃气管理条例', '北京市人民代表大会常务委员会', '北京市', '地方性法规', '2020-12-01', '2021-01-01', '对燃气经营、使用和设施保护作出规定，要求燃气企业加强管网巡检，及时消除安全隐患。', ['燃气', '巡检'], null, 'PUBLISHED'],
  ]
  return rows.map((r, i) => ({
    id: uuid(500 + i), title: r[0], authority: r[1], level: r[2], category: r[3],
    publishDate: r[4], effectiveDate: r[5], status: r[8],
    summary: r[6], tags: r[7], documentNumber: r[9], sourceUrl: null,
    associationId: ASSOCIATION_ID, visibility: 'ASSOCIATION', version: 1,
    disabled: false, deleted: false, updatedAt: isoDate(2026, 8, 15 + i),
  }))
}

function fallbackTenders() {
  const rows = [
    ['昌平区回龙观街道2026年老旧小区供热管网改造工程（施工）', '北京市昌平区城市管理委员会', '昌平区', '热力管网', ['热力管网改造', '老旧小区', '施工'], 18600000, '2026-07-15', '2026-08-05', '北京市公共资源交易服务平台', 'ACTIVE'],
    ['天通苑北三区雨污分流改造及排水管网修复工程', '北京市昌平区天通苑街道办事处', '昌平区', '排水管网', ['排水管网', '雨污分流', '修复'], 9200000, '2026-07-22', '2026-08-12', '北京市公共资源交易服务平台', 'ACTIVE'],
    ['昌平区回天地区地下管线综合探测普查项目', '北京市规划和自然资源委员会昌平分局', '昌平区', '管线探测', ['管线探测', '普查', '测绘'], 4600000, '2026-08-02', '2026-08-22', '中国招标投标公共服务平台', 'ACTIVE'],
    ['海淀区中关村科学城燃气管网隐患治理工程', '北京海淀燃气有限公司', '海淀区', '燃气管网', ['燃气管网', '隐患治理'], 13500000, '2026-08-05', '2026-08-25', '北京市公共资源交易服务平台', 'ACTIVE'],
    ['朝阳区东坝片区供水管网改造及智能水表更换工程', '北京市朝阳区水务局', '朝阳区', '供水管网', ['供水管网', '智能水表'], 7800000, '2026-08-10', '2026-08-30', '北京市政府采购网', 'ACTIVE'],
    ['丰台区南苑街道非开挖管道修复工程（CIPP）', '北京丰台水务发展有限公司', '丰台区', '非开挖修复', ['非开挖修复', 'CIPP'], 3200000, '2026-08-12', '2026-09-01', '中国招标投标公共服务平台', 'ACTIVE'],
    ['通州区运河商务区电力管沟建设工程（土建部分）', '北京市通州区城市管理委员会', '通州区', '电力管沟', ['电力管沟', '隧道'], 15200000, '2026-08-15', '2026-09-04', '北京市公共资源交易服务平台', 'ACTIVE'],
    ['大兴区生物医药基地污水管网检测评估服务', '北京市大兴区水务局', '大兴区', '检测评估', ['污水管网', 'CCTV检测', '评估'], 1800000, '2026-08-18', '2026-09-07', '北京市政府采购网', 'ACTIVE'],
  ]
  return rows.map((r, i) => ({
    id: uuid(900 + i), title: r[0], purchaser: r[1], agency: null, region: r[2], category: r[3],
    keywords: r[4], budget: r[5], publishDate: r[6], deadline: r[7], source: r[8],
    sourceUrl: null, status: r[9], version: 1, createdAt: isoDate(2026, 8, 1 + i), updatedAt: isoDate(2026, 8, 1 + i),
  }))
}

// ------------------------------------------------------------------ 运行状态 --
const state = {
  members: loadJson('members.json', fallbackMembers()),
  policies: loadJson('policies.json', fallbackPolicies()),
  tenders: loadJson('tenders.json', fallbackTenders()),
  tenderPushes: [],        // {id, tenderId, tenderTitle, enterpriseId, enterpriseName, pushedBySubject, pushedAt, status, version}
  offerings: [], demands: [], matches: [],
  invitations: [], negotiations: [], feedback: [], outcomes: [],
  collaborations: [], collaborationActivities: [], attachments: [],
  notifications: [], subscriptions: [], accessRequests: [], relationships: [],
  importBatches: new Map(),
}

// ------------------------------------------------- 派生生态数据（一次初始化）----
const memberById = (id) => state.members.find((m) => m.id === id)
const activeMembers = () => state.members.filter((m) => m.status === 'ACTIVE')
const sceneNames = ['供水', '燃气', '热力', '排水', '电力', '通信', '道路', '综合管廊']
const nextSeq = (() => { let n = 50000; return () => (n += 1) })()
const seqId = () => `demo-${nextSeq()}`

function initDerived() {
  const byId = new Map(state.members.map((m) => [m.id, m]))
  const list = [...byId.values()]
  const pick = (idx) => (list[idx % list.length] || list[0])
  const pubTime = isoDate(2026, 8, 24)

  // 产品/服务 → Offering
  let o = 0
  state.offerings = []
  for (const m of list) {
    if (m.status !== 'ACTIVE') continue
    for (const p of (m.products || [])) {
      if (o >= 40) break
      state.offerings.push({
        id: seqId(), enterpriseId: m.id, enterpriseName: m.name, name: p,
        kind: /服务|咨询|检测|探测|评估|测绘/.test(p) ? 'SERVICE' : 'PRODUCT',
        description: `${m.name}提供的${p}，面向${(m.scenes || []).join('、') || '城市管线'}场景。`,
        scenarios: m.scenes || [], qualifications: m.capabilities || [],
        visibility: 'ASSOCIATION', status: 'PUBLISHED', version: 1, disabled: false, updatedAt: pubTime,
      })
      o += 1
    }
  }

  // 需求 → Demand（含京城管网的需求）
  const demandRows = [
    [ENTERPRISE_ID, '供热管网改造专业分包需求（回龙观片区）', '回龙观地区老旧小区供热管网改造即将开工，需具备特种设备安装资质的热力管道施工分包队伍。', ['热力'], ['热力管网施工'], 'ASSOCIATION', 8000000, 12000000, '2026-09-30'],
    [pick(2).id, '寻找燃气管道带压开孔作业服务商', '我司承接燃气管网改造总包，需外协带压开孔与不停输作业服务。', ['燃气'], ['带压开孔'], 'MEMBERS', 500000, 1500000, '2026-10-15'],
    [pick(4).id, '智能阀门配套控制单元采购意向', '拟采购与智能阀门配套的电动执行器与远程控制单元。', ['供水', '燃气'], ['电动执行器', '远程控制'], 'MEMBERS', 2000000, 4000000, '2026-11-01'],
    [pick(5).id, '污水管网CCTV检测外包需求', '年度管网检测任务量大，需检测机构协助完成CCTV检测与缺陷评估。', ['排水'], ['CCTV检测'], 'MEMBERS', 600000, 1200000, '2026-10-20'],
  ]
  state.demands = demandRows.map((r) => ({
    id: seqId(), enterpriseId: r[0], enterpriseName: byId.get(r[0])?.name || '会员企业',
    title: r[1], description: r[2], scenarios: r[3], requiredCapabilities: r[4],
    visibility: r[5], budgetMin: r[6], budgetMax: r[7], responseDeadline: r[8],
    status: 'PUBLISHED', closeReason: null, version: 1, disabled: false, updatedAt: pubTime,
  }))

  // 生态匹配（PersistedMatch），分数分项与总分一致（100 分制）
  const matchRows = [
    [0, 2, 96, 'RECOMMENDED', ['应用场景匹配 35/35', '供需能力匹配 24/25', '技术资质匹配 14/15', '案例与交付 10/10', '地区与交付 10/10', '资料完整度 3/5']],
    [1, 4, 91, 'PENDING_CONFIRMATION', ['应用场景匹配 35/35', '供需能力匹配 22/25', '技术资质匹配 13/15', '案例与交付 8/10', '地区与交付 9/10', '资料完整度 4/5']],
    [2, 3, 88, 'NEGOTIATING', ['应用场景匹配 32/35', '供需能力匹配 23/25', '技术资质匹配 13/15', '案例与交付 9/10', '地区与交付 8/10', '资料完整度 3/5']],
  ]
  state.matches = matchRows.map((r) => {
    const demand = state.demands[r[0]]
    const supplier = state.members[r[1] % state.members.length]
    const nowIso = now()
    const confirmed = r[2] >= 90 ? nowIso : null
    return {
      id: seqId(), demandId: demand.id, demandEnterpriseId: demand.enterpriseId,
      demandCompany: demand.enterpriseName, demandTitle: demand.title, scene: (demand.scenarios || [])[0] || '综合',
      supplierCompany: supplier.name, solution: (supplier.products || supplier.capabilities || ['综合服务'])[0],
      candidateEnterpriseId: supplier.id, score: r[2], reasons: r[3], state: r[4],
      recommendedAt: nowIso, demandConfirmedAt: confirmed, candidateConfirmedAt: r[2] >= 95 ? confirmed : null,
      closedReason: null, version: 1, updatedAt: nowIso,
    }
  })

  // 协作事项
  const collabRows = [
    ['回天地区供热改造配套协作', ['京城管网科技有限公司', '北京恒源热力工程有限公司'], '协会运营部', 'IN_PROGRESS', '高', '对接改造进度与监测设备安装排期', day(5), 60, '北京地下管线协会'],
    ['昌平区管线普查数据对接', ['北京瑞通管线探测有限公司', '北京中勘测绘有限公司'], '协会运营部', 'OPEN', '中', '组织两家单位对齐普查标准与成果格式', day(9), 20, '北京地下管线协会'],
    ['政策解读宣贯：北京市地下管线管理办法', ['北京地下管线协会'], '协会秘书处', 'OPEN', '中', '确定宣贯会时间并通知会员单位', day(12), 10, '北京地下管线协会'],
    ['智能阀门集采调研', ['北京安燃阀门制造有限公司'], '协会秘书处', 'PROPOSED', '低', '收集会员单位阀门采购需求数量', day(15), 0, '北京地下管线协会'],
    ['服贸会联合展示筹备', ['北京地下管线协会', '京城管网科技有限公司'], '协会秘书处', 'IN_PROGRESS', '高', '整理展位演示物料与话术', day(3), 80, '北京地下管线协会'],
  ]
  state.collaborations = collabRows.map((r) => ({
    id: seqId(), title: r[0], participants: r[1], owner: r[2], stage: r[3], priority: r[4],
    nextAction: r[5], dueDate: r[6], progress: r[7], matchId: null,
    associationId: ASSOCIATION_ID, enterpriseId: null, version: 1, disabled: false, deleted: false, updatedAt: pubTime,
  }))
  state.collaborationActivities = state.collaborations.slice(0, 3).map((c, i) => ({
    id: i + 1, type: '进度更新', detail: '事项已建立，进入推进阶段', actorSubject: '协会运营部', occurredAt: pubTime,
  }))

  // 附件
  state.attachments = [
    ['2026年会员企业基础调查表（模板）.xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 26840],
    ['北京市地下管线管理办法.pdf', 'application/pdf', 184230],
    ['回天地区试点数据收集说明.docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', 45300],
    ['管线智联平台功能介绍.pdf', 'application/pdf', 926400],
  ].map((r, i) => ({
    id: seqId(), associationId: ASSOCIATION_ID, enterpriseId: null,
    originalFilename: r[0], mediaType: r[1], sizeBytes: r[2],
    sha256: `a${String(i).repeat(63)}`, scanStatus: 'CLEAN', visibility: 'ASSOCIATION',
    status: 'ACTIVE', version: 1, uploadedAt: isoDate(2026, 8, 10 + i), updatedAt: isoDate(2026, 8, 10 + i), deletedAt: null,
  }))

  // 通知
  const notifRows = [
    ['TENDER', '新增招标：昌平区回天地区地下管线综合探测普查项目', '回龙观地区新发布探测普查招标，预算约460万元，请相关会员及时查看。', 'TENDER', null, isoDate(2026, 8, 2, 10), false],
    ['TENDER', '新增招标：天通苑北三区排水管网修复工程', '排水管网修复招标已发布，与我司能力相关。', 'TENDER', null, isoDate(2026, 7, 22, 15), false],
    ['POLICY', '政策更新：北京市地下管线管理办法', '新修订管理办法涉及档案动态更新与道路重复开挖限制，请会员单位核对合规事项。', 'POLICY', null, isoDate(2026, 8, 15, 9), false],
    ['MATCH', '匹配推荐：供热改造专业分包需求', '京城管网科技有限公司提出热力施工分包需求，系统推荐了候选服务商。', 'MATCH', null, isoDate(2026, 8, 20, 11), true],
    ['MEMBER', '会员档案待完善提醒', '贵企业资料完整度为45%，完善产品与服务信息可获得更精准匹配推荐。', 'MEMBER', null, isoDate(2026, 8, 25, 14), true],
    ['COLLABORATION', '协作事项更新：服贸会联合展示筹备', '展示物料清单已更新，请查看下一步行动。', 'COLLABORATION', null, isoDate(2026, 8, 26, 16), true],
  ]
  state.notifications = notifRows.map((r, i) => ({
    id: seqId(), notificationType: r[0], title: r[1], body: r[2], resourceType: r[3], resourceId: r[4],
    status: r[6] ? 'UNREAD' : 'READ', readAt: r[6] ? null : r[5], createdAt: r[5], deliveredAt: r[5],
  }))

  // 订阅
  state.subscriptions = [
    { id: seqId(), userId: 'u-004', associationId: ASSOCIATION_ID, subscriptionType: 'TENDER', filters: { categories: ['热力管网', '管线探测'] }, channels: ['APP', 'EMAIL'], status: 'ACTIVE', version: 1, createdAt: isoDate(2026, 8, 1), updatedAt: isoDate(2026, 8, 1) },
    { id: seqId(), userId: 'u-004', associationId: ASSOCIATION_ID, subscriptionType: 'POLICY', filters: { levels: ['国家', '北京市'] }, channels: ['APP'], status: 'ACTIVE', version: 1, createdAt: isoDate(2026, 8, 1), updatedAt: isoDate(2026, 8, 1) },
  ]

  // 友好协会
  state.relationships = [
    { sourceAssociationId: ASSOCIATION_ID, targetAssociationId: uuid(777), targetAssociationName: '上海市管线管理协会', status: 'ACTIVE', allowMemberData: true, expiresAt: null, version: 1, updatedAt: isoDate(2026, 8, 12) },
    { sourceAssociationId: ASSOCIATION_ID, targetAssociationId: uuid(778), targetAssociationName: '北京市照明电器行业协会', status: 'PENDING', allowMemberData: false, expiresAt: null, version: 1, updatedAt: isoDate(2026, 8, 18) },
  ]
  state.accessRequests = [
    { id: seqId(), applicantAssociationId: uuid(777), applicantAssociationName: '上海市管线管理协会', targetAssociationId: ASSOCIATION_ID, reason: '希望共享两地非开挖修复服务商目录，推动跨区域合作。', status: 'PENDING', requestedBySubject: 'shanghai-admin', reviewedBySubject: null, reviewComment: null, requestedAt: isoDate(2026, 8, 10), reviewedAt: null },
    { id: seqId(), applicantAssociationId: ASSOCIATION_ID, applicantAssociationName: '北京地下管线协会', targetAssociationId: uuid(778), reason: '拟在服贸会期间与照明协会开展联合展示。', status: 'PENDING', requestedBySubject: 'beijing-admin', reviewedBySubject: null, reviewComment: null, requestedAt: isoDate(2026, 8, 18), reviewedAt: null },
  ]

  // 预置招标推送记录（体现"协会主动推送"，重点覆盖京城管网等示范企业）
  const demoEnterprise = memberById(ENTERPRISE_ID)
  const pushTargets = [demoEnterprise, activeMembers()[0], activeMembers()[1]].filter(Boolean)
  const pushTender = (tenderIndex) => {
    const tender = state.tenders[tenderIndex]
    if (!tender) return
    for (const m of pushTargets) {
      if (state.tenderPushes.some((x) => x.tenderId === tender.id && x.enterpriseId === m.id)) continue
      state.tenderPushes.push({
        id: seqId(), tenderId: tender.id, tenderTitle: tender.title, enterpriseId: m.id,
        enterpriseName: m.name, pushedBySubject: 'beijing-association',
        pushedAt: isoDate(2026, 8, 26, 9), status: 'PUSHED', version: 1,
      })
    }
  }
  if (pushTargets.length) {
    pushTender(0) // 回龙观供热改造（热力）→ 京城管网（热力场景）
    pushTender(2) // 回天管线探测普查
  }
}
initDerived()

// --------------------------------------------------------------------- 工具 ----
const json = (res, status, payload, extraHeaders = {}) => {
  const body = JSON.stringify(payload)
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
    ...extraHeaders,
  })
  res.end(body)
}
const error = (res, status, code, message) => json(res, status, { code, message })

function roleOf(req) {
  const value = req.headers['x-guanxian-demo-role']
  return ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR', 'ENTERPRISE_ADMIN', 'ENTERPRISE_MEMBER'].includes(value) ? value : 'ASSOCIATION_ADMIN'
}
const isAssociationStaff = (role) => ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR'].includes(role)
const isEnterprise = (role) => role === 'ENTERPRISE_ADMIN' || role === 'ENTERPRISE_MEMBER'
function enterpriseOf(req) {
  const header = req.headers['x-guanxian-enterprise-id']
  if (header && state.members.some((m) => m.id === header)) return header
  return isEnterprise(roleOf(req)) ? ENTERPRISE_ID : null
}
function subjectOf(req) {
  const map = { SYSTEM_ADMIN: 'platform-admin', ASSOCIATION_ADMIN: 'zhang-quanchao', ASSOCIATION_OPERATOR: 'xu-ming', ENTERPRISE_ADMIN: 'wang-zhiyuan', ENTERPRISE_MEMBER: 'li-nan' }
  return map[roleOf(req)] || 'beijing-association'
}
function demoUser(req) {
  const role = roleOf(req)
  const base = {
    SYSTEM_ADMIN: { name: '平台管理员', organization: '管线智联平台', title: '系统管理员' },
    ASSOCIATION_ADMIN: { name: '张全超', organization: '北京地下管线协会', title: '协会管理员' },
    ASSOCIATION_OPERATOR: { name: '徐明', organization: '北京地下管线协会', title: '会员服务专员' },
    ENTERPRISE_ADMIN: { name: '王志远', organization: '京城管网科技有限公司', title: '企业管理员' },
    ENTERPRISE_MEMBER: { name: '李楠', organization: '京城管网科技有限公司', title: '市场经理' },
  }[role]
  return {
    subject: role, username: role, displayName: base.name, organization: base.organization,
    title: base.title, roles: [role], permissions: [],
    associationId: role === 'SYSTEM_ADMIN' ? null : ASSOCIATION_ID,
    enterpriseId: isEnterprise(role) ? ENTERPRISE_ID : null,
  }
}

const query = (url) => Object.fromEntries(new URL(url, 'http://localhost').searchParams)
const qs = (params) => new URLSearchParams(params).toString()
const pageSlice = (arr, p, s) => {
  const page = Math.max(0, Number(p) || 0)
  const size = Math.min(100, Math.max(1, Number(s) || 20))
  return { items: arr.slice(page * size, page * size + size), total: arr.length, page, size }
}

function matchScore(member, tender) {
  if (!member) return 0
  const words = new Set([
    ...(member.products || []), ...(member.capabilities || []), ...(member.scenes || []),
    member.role || '', member.category || '',
  ].join('、').split(/[、，,\s/]+/).filter((w) => w.length >= 2))
  let score = 0
  const hay = `${tender.title} ${tender.keywords.join(' ')} ${tender.category}`
  for (const w of words) if (hay.includes(w)) score += 1
  if ((member.scenes || []).some((s) => (tender.category || '').includes(s.slice(0, 2))) || (tender.keywords || []).some((k) => (member.scenes || []).some((s) => k.includes(s)))) score += 1
  return score
}
function relevantTenders(member) {
  return state.tenders
    .map((t) => ({ item: t, score: matchScore(member, t) }))
    .filter((r) => r.score > 0)
    .sort((a, b) => b.score - a.score || String(b.item.publishDate).localeCompare(String(a.item.publishDate)))
}
function tenderView(t, role, enterpriseId) {
  const pushes = state.tenderPushes.filter((p) => p.tenderId === t.id)
  const view = { ...t, associationId: t.associationId || ASSOCIATION_ID, pushCount: pushes.length }
  if (isEnterprise(role)) {
    view.pushedToMe = enterpriseId ? pushes.some((p) => p.enterpriseId === enterpriseId) : false
    const rel = relevantTenders(memberById(enterpriseId || ''))
    const found = rel.find((r) => r.item.id === t.id)
    view.relevance = found ? found.score : 0
  }
  return view
}

const memberListShape = (m, role) => ({
  id: m.id, name: m.name, shortName: m.shortName || m.name.slice(0, 4),
  role: m.role || m.category || '', scenes: m.scenes || [], products: m.products || [],
  city: m.district || '北京市', contact: `${m.contactName || ''} ${m.contactPhone || ''}`.trim(),
  completeness: m.completeness ?? 0,
  status: ({ ACTIVE: '已认证', INCOMPLETE: '待完善', PENDING_REVIEW: '待审核', DISABLED: '已停用', DELETED: '已删除' })[m.status] || m.status,
  visibility: m.visibility, canEdit: role === 'ENTERPRISE_ADMIN' || isAssociationStaff(role),
  canReview: ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN'].includes(role),
  version: m.version || 1, updatedAt: m.updatedAt, deletedAt: m.deletedAt,
})

// ------------------------------------------------------------ HTTP 服务器 ----
async function readBody(req) {
  const type = (req.headers['content-type'] || '').toLowerCase()
  if (type.startsWith('multipart/')) {
    const web = new Request(`http://localhost${req.url}`, { method: req.method, headers: req.headers, body: Readable.toWeb(req), duplex: 'half' })
    return web.formData()
  }
  const chunks = []
  let size = 0
  for await (const chunk of req) {
    size += chunk.length
    if (size > 12 * 1024 * 1024) throw Object.assign(new Error('body too large'), { status: 413 })
    chunks.push(chunk)
  }
  const text = Buffer.concat(chunks).toString('utf8')
  return text ? JSON.parse(text) : {}
}

const router = []

function route(method, pattern, handler) {
  const keys = []
  const source = String(pattern).replace(/:[A-Za-z]+/g, (m) => { keys.push(m.slice(1)); return '([^/]+)' })
  router.push({
    method, keys,
    regex: new RegExp(`^${source}/?$`),
    handler: async (req, res, match) => {
      const params = {}
      keys.forEach((k, i) => { params[k] = decodeURIComponent(match[i + 1]) })
      return handler(req, res, params)
    },
  })
}

route('GET', '/api/v1/health', (_req, res) => json(res, 200, { status: 'UP', service: 'guanxian-demo', time: now() }))
route('GET', '/api/v1/users/me', (req, res) => json(res, 200, demoUser(req)))

// ---- 系统上下文 ----
route('GET', '/api/v1/system-context/associations', (_req, res) => json(res, 200, [{ id: ASSOCIATION_ID, name: '北京地下管线协会' }]))
route('GET', '/api/v1/system-context/enterprises', (_req, res, p) => {
  const target = query(_req.url).associationId || ASSOCIATION_ID
  if (target !== ASSOCIATION_ID) return json(res, 200, [])
  const items = activeMembers().slice(0, 60).map((m) => ({ id: m.id, associationId: ASSOCIATION_ID, name: m.name }))
  json(res, 200, items)
})

// ---- 会员企业 ----
route('GET', '/api/v1/members/distribution', (_req, res) => {
  const list = state.members.filter((m) => m.status !== 'DELETED')
  const districtCount = new Map()
  const productCount = new Map()
  const categoryCount = new Map()
  for (const m of list) {
    const d = m.district || '北京市'
    districtCount.set(d, (districtCount.get(d) || 0) + 1)
    categoryCount.set(m.category || '其他', (categoryCount.get(m.category || '其他') || 0) + 1)
    for (const p of m.products || []) productCount.set(p, (productCount.get(p) || 0) + 1)
  }
  const total = list.length || 1
  const toDist = (map) => [...map.entries()].map(([name, count]) => ({ name, count, percent: Math.round((count / total) * 1000) / 10 }))
  json(res, 200, {
    total,
    districts: toDist(districtCount).sort((a, b) => b.count - a.count),
    products: [...productCount.entries()].map(([name, count]) => ({ name, count })).sort((a, b) => b.count - a.count).slice(0, 12),
    categories: toDist(categoryCount).sort((a, b) => b.count - a.count),
  })
})
route('GET', '/api/v1/members/page', (req, res) => {
  const q = query(req.url)
  const kw = (q.q || q.query || '').trim()
  const statusCode = q.status || ''
  const includeDeleted = q.includeDeleted === 'true'
  let list = state.members
  if (statusCode) list = list.filter((m) => m.status === statusCode)
  if (!includeDeleted) list = list.filter((m) => m.status !== 'DELETED')
  if (kw) list = list.filter((m) => `${m.name}${m.shortName || ''}${m.introduction || ''}${m.role || ''}`.includes(kw))
  const role = roleOf(req)
  const page = pageSlice(list, q.page, q.size)
  json(res, 200, { ...page, items: page.items.map((m) => memberListShape(m, role)) })
})
route('GET', '/api/v1/members', (req, res) => {
  const q = query(req.url)
  const kw = (q.query || '').trim()
  const statusCode = q.status || ''
  const includeDeleted = q.includeDeleted === 'true'
  let list = state.members
  if (statusCode) list = list.filter((m) => m.status === statusCode)
  if (!includeDeleted) list = list.filter((m) => m.status !== 'DELETED')
  if (kw) list = list.filter((m) => `${m.name}${m.shortName || ''}${m.introduction || ''}${m.role || ''}`.includes(kw))
  const role = roleOf(req)
  const page = pageSlice(list, q.page, q.size)
  json(res, 200, { ...page, items: page.items.map((m) => memberListShape(m, role)) })
})
route('GET', '/api/v1/members/import-template', (_req, res) => {
  const file = path.join(__dirname, 'data', 'member-import-template.xlsx')
  if (existsSync(file)) {
    const buf = readFileSync(file)
    res.writeHead(200, {
      'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      'Content-Length': buf.length,
      'Content-Disposition': 'attachment; filename="member-import-template.xlsx"',
    })
    res.end(buf)
  } else {
    const body = Buffer.from('模板文件尚未生成，请运行 scripts/build_import_template.py 生成', 'utf8')
    res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8', 'Content-Length': body.length })
    res.end(body)
  }
})
route('GET', '/api/v1/members/:id', (req, res, p) => {
  const q = query(req.url)
  const m = state.members.find((x) => x.id === p.id && (q.includeDeleted === 'true' || x.status !== 'DELETED'))
  if (!m) return error(res, 404, 'MEMBER_NOT_FOUND', '会员企业不存在', req.headers['x-request-id'])
  const profile = {
    id: m.id, associationId: m.associationId, name: m.name, unifiedSocialCreditCode: m.unifiedSocialCreditCode || null,
    category: m.category, address: m.address || null, contactName: m.contactName || null, contactPhone: m.contactPhone || null,
    introduction: m.introduction || null, capabilities: m.capabilities || [], products: m.products || [],
    cooperationNeeds: m.cooperationNeeds || [], visibility: m.visibility, status: m.status,
    version: m.version || 1, createdAt: m.createdAt, updatedAt: m.updatedAt, deletedAt: m.deletedAt,
    deletedBySubject: m.deletedBySubject || null, statusBeforeDelete: m.statusBeforeDelete || null,
  }
  json(res, 200, profile, { ETag: `"${m.version || 1}"` })
})
route('POST', '/api/v1/members', async (req, res) => {
  const body = await readBody(req)
  const id = seqId()
  const m = {
    id, associationId: body.associationId || ASSOCIATION_ID, name: body.name || '未命名企业',
    shortName: (body.name || '').slice(0, 4), unifiedSocialCreditCode: body.unifiedSocialCreditCode || null,
    category: body.category || '工程服务商', address: body.address || null, district: body.address?.includes('昌平') ? '昌平区' : '海淀区',
    contactName: body.contactName || null, contactPhone: body.contactPhone || null, introduction: body.introduction || null,
    capabilities: body.capabilities || [], products: body.products || [], cooperationNeeds: body.cooperationNeeds || [],
    scenes: [], role: body.category || '', visibility: body.visibility || 'ASSOCIATION',
    status: 'PENDING_REVIEW', completeness: 35, version: 1, createdAt: now(), updatedAt: now(), deletedAt: null,
  }
  state.members.push(m)
  json(res, 200, m)
})
route('PUT', '/api/v1/members/:id', async (req, res, p) => {
  const m = state.members.find((x) => x.id === p.id)
  if (!m) return error(res, 404, 'MEMBER_NOT_FOUND', '会员企业不存在', req.headers['x-request-id'])
  const ifMatch = req.headers['if-match']
  if (ifMatch && ifMatch !== `"${m.version || 1}"`) return error(res, 412, 'VERSION_CONFLICT', '数据已发生变化，请刷新后重试。', req.headers['x-request-id'])
  const body = await readBody(req)
  const fields = ['name', 'unifiedSocialCreditCode', 'category', 'address', 'contactName', 'contactPhone', 'introduction', 'capabilities', 'products', 'cooperationNeeds', 'visibility', 'status']
  for (const f of fields) if (f in body) m[f] = body[f]
  m.version = (m.version || 1) + 1
  m.updatedAt = now()
  json(res, 200, m, { ETag: `"${m.version}"` })
})
route('PUT', '/api/v1/members/:id/review', async (req, res, p) => {
  const m = state.members.find((x) => x.id === p.id)
  if (!m) return error(res, 404, 'MEMBER_NOT_FOUND', '会员企业不存在', req.headers['x-request-id'])
  const body = await readBody(req)
  m.status = body.approved ? 'ACTIVE' : 'INCOMPLETE'
  m.version = (m.version || 1) + 1
  m.updatedAt = now()
  json(res, 200, m, { ETag: `"${m.version}"` })
})
route('DELETE', '/api/v1/members/:id', (req, res, p) => {
  const m = state.members.find((x) => x.id === p.id)
  if (!m) return error(res, 404, 'MEMBER_NOT_FOUND', '会员企业不存在', req.headers['x-request-id'])
  const ifMatch = req.headers['if-match']
  if (ifMatch && ifMatch !== `"${m.version || 1}"`) return error(res, 412, 'VERSION_CONFLICT', '数据已发生变化，请刷新后重试。', req.headers['x-request-id'])
  m.statusBeforeDelete = m.status
  m.status = 'DELETED'
  m.deletedAt = now()
  m.deletedBySubject = subjectOf(req)
  m.version = (m.version || 1) + 1
  json(res, 200, { deleted: true, id: m.id, version: m.version })
})
route('PUT', '/api/v1/members/:id/restore', (req, res, p) => {
  const m = state.members.find((x) => x.id === p.id)
  if (!m) return error(res, 404, 'MEMBER_NOT_FOUND', '会员企业不存在', req.headers['x-request-id'])
  m.status = m.statusBeforeDelete || 'INCOMPLETE'
  m.deletedAt = null
  m.deletedBySubject = null
  m.version = (m.version || 1) + 1
  json(res, 200, m, { ETag: `"${m.version}"` })
})

// ---- 会员批量导入（演示预检/提交闭环）----
route('POST', '/api/v1/members/imports/preview', async (req, res) => {
  const form = await readBody(req)
  const file = form.get('file')
  const batchId = seqId()
  const sample = activeMembers().slice(0, 6)
  const rows = sample.map((m, i) => ({
    rowNumber: i + 1,
    data: {
      name: m.name, unifiedSocialCreditCode: m.unifiedSocialCreditCode, category: m.category,
      address: m.address, contactName: m.contactName, contactPhone: m.contactPhone,
      introduction: m.introduction, capabilities: m.capabilities, products: m.products,
      cooperationNeeds: m.cooperationNeeds,
    },
    errors: [],
    status: 'VALID',
    enterpriseId: m.id,
  }))
  rows.push({ rowNumber: 7, data: { name: '示例企业七', unifiedSocialCreditCode: 'invalid-code', category: '工程服务商', address: null, contactName: null, contactPhone: null, introduction: null, capabilities: [], products: [], cooperationNeeds: [] }, errors: ['统一社会信用代码格式不正确', '企业名称不能为空'[0] && ''], status: 'INVALID', enterpriseId: null })
  rows[6].errors = ['统一社会信用代码格式不正确']
  const preview = {
    batchId, filename: file ? (file.name || '会员调查表.xlsx') : '会员调查表.xlsx',
    status: 'PREVIEWED', totalRows: rows.length,
    validRows: rows.filter((r) => r.status === 'VALID').length,
    invalidRows: rows.filter((r) => r.status === 'INVALID').length,
    createdAt: now(), rows,
  }
  state.importBatches.set(batchId, preview)
  json(res, 200, preview)
})
route('GET', '/api/v1/members/imports/:batchId', (req, res, p) => {
  const batch = state.importBatches.get(p.batchId)
  if (!batch) return error(res, 404, 'BATCH_NOT_FOUND', '导入批次不存在', req.headers['x-request-id'])
  json(res, 200, batch)
})
route('POST', '/api/v1/members/imports/:batchId/commit', (req, res, p) => {
  const batch = state.importBatches.get(p.batchId)
  if (!batch) return error(res, 404, 'BATCH_NOT_FOUND', '导入批次不存在', req.headers['x-request-id'])
  const valid = batch.rows.filter((r) => r.status === 'VALID')
  const ids = valid.map((r) => {
    const exists = state.members.some((m) => m.unifiedSocialCreditCode === r.data.unifiedSocialCreditCode)
    if (exists) return r.enterpriseId
    const m = { id: seqId(), associationId: ASSOCIATION_ID, name: r.data.name, shortName: r.data.name.slice(0, 4), unifiedSocialCreditCode: r.data.unifiedSocialCreditCode, category: r.data.category, address: r.data.address, district: '海淀区', contactName: r.data.contactName, contactPhone: r.data.contactPhone, introduction: r.data.introduction, capabilities: r.data.capabilities || [], products: r.data.products || [], cooperationNeeds: [], scenes: [], role: r.data.category, visibility: 'ASSOCIATION', status: 'PENDING_REVIEW', completeness: 50, version: 1, createdAt: now(), updatedAt: now(), deletedAt: null }
    state.members.push(m)
    return m.id
  })
  batch.status = 'COMMITTED'
  json(res, 200, { batchId: batch.batchId, importedRows: valid.length, invalidRows: batch.invalidRows, enterpriseIds: ids })
})

// ---- 政策标准 ----
route('GET', '/api/v1/policies/levels', (_req, res) => json(res, 200, ['国家', '北京市', '行业协会']))
route('GET', '/api/v1/policies/page', (req, res) => {
  const q = query(req.url)
  const kw = (q.q || q.query || '').trim()
  let list = state.policies.filter((p) => !p.deleted && (q.includeDeleted === 'true' || !p.disabled))
  if (q.level) list = list.filter((p) => p.level === q.level)
  if (kw) list = list.filter((p) => `${p.title}${p.summary}${(p.tags || []).join('')}${p.authority}`.includes(kw))
  const page = pageSlice(list, q.page, q.size)
  json(res, 200, { ...page, items: page.items.map((p) => ({ ...p, version: p.version || 1 })) })
})
route('GET', '/api/v1/policies', (req, res) => {
  const q = query(req.url)
  const kw = (q.query || '').trim()
  let list = state.policies.filter((p) => !p.deleted && (q.includeDeleted === 'true' || !p.disabled))
  if (q.level) list = list.filter((p) => p.level === q.level)
  if (kw) list = list.filter((p) => `${p.title}${p.summary}${(p.tags || []).join('')}${p.authority}`.includes(kw))
  const page = pageSlice(list, q.page, q.size)
  json(res, 200, { ...page, items: page.items.map((p) => ({ ...p, version: p.version || 1 })) })
})
route('GET', '/api/v1/policies/:id', (req, res, p) => {
  const item = state.policies.find((x) => x.id === p.id)
  if (!item) return error(res, 404, 'POLICY_NOT_FOUND', '政策不存在', req.headers['x-request-id'])
  json(res, 200, { ...item, version: item.version || 1 }, { ETag: `"${item.version || 1}"` })
})
route('POST', '/api/v1/policies', async (req, res) => {
  const body = await readBody(req)
  const item = {
    id: seqId(), title: body.title, authority: body.authority || '北京地下管线协会', level: body.level || '北京市',
    category: body.category || '协会文件', publishDate: body.publishDate || now().slice(0, 10),
    effectiveDate: body.effectiveDate || null, status: 'DRAFT', summary: body.summary || '',
    tags: body.tags || [], documentNumber: body.documentNumber || null, sourceUrl: body.sourceUrl || null,
    associationId: ASSOCIATION_ID, visibility: body.visibility || 'MEMBERS', version: 1,
    disabled: false, deleted: false, updatedAt: now(),
  }
  state.policies.unshift(item)
  json(res, 200, item)
})
route('PUT', '/api/v1/policies/:id', async (req, res, p) => {
  const item = state.policies.find((x) => x.id === p.id)
  if (!item) return error(res, 404, 'POLICY_NOT_FOUND', '政策不存在', req.headers['x-request-id'])
  const body = await readBody(req)
  Object.assign(item, { title: body.title, authority: body.authority, level: body.level, category: body.category, publishDate: body.publishDate, effectiveDate: body.effectiveDate, summary: body.summary, tags: body.tags, documentNumber: body.documentNumber, sourceUrl: body.sourceUrl, visibility: body.visibility })
  item.version += 1
  item.updatedAt = now()
  json(res, 200, item)
})
route('POST', '/api/v1/policies/:id/submit', (req, res, p) => {
  const item = state.policies.find((x) => x.id === p.id)
  if (!item) return error(res, 404, 'POLICY_NOT_FOUND', '政策不存在', req.headers['x-request-id'])
  item.status = 'PENDING_REVIEW'
  item.version += 1
  json(res, 200, item)
})
route('PUT', '/api/v1/policies/:id/review', async (req, res, p) => {
  const item = state.policies.find((x) => x.id === p.id)
  if (!item) return error(res, 404, 'POLICY_NOT_FOUND', '政策不存在', req.headers['x-request-id'])
  const body = await readBody(req)
  item.status = body.approved ? 'PUBLISHED' : 'DRAFT'
  item.version += 1
  item.updatedAt = now()
  json(res, 200, item)
})
route('GET', '/api/v1/policy-impact-analyses/page', (_req, res) => {
  const levelOrder = { 高: 0, 中: 1, 低: 2 }
  const active = activeMembers().slice(0, 14)
  const pub = state.policies.filter((p) => p.status === 'PUBLISHED').slice(0, 6)
  const items = []
  const texts = [
    ['高', '该企业应结合条文要求补充完善本单位风险分级管控方法，并建立年度改造计划与项目复核机制。'],
    ['中', '政策涉及数据治理与台账校核要求，该企业需在承接业务时提供数据治理台账与校核服务说明。'],
    ['低', '该企业与政策关联度有限，建议关注其中关于档案管理的一般性要求。'],
  ]
  let n = 0
  for (const pol of pub) {
    for (const m of active.slice(0, 3)) {
      const t = texts[n % texts.length]
      items.push({
        id: seqId(), policyDocumentId: pol.id, policyTitle: pol.title,
        enterpriseId: m.id, enterpriseName: m.name, associationId: ASSOCIATION_ID,
        impactLevel: t[0], summary: t[1], evidenceChunkIds: [], status: 'GENERATED',
        version: 1, updatedAt: now(), analysisMethod: 'RULES',
      })
      n += 1
      if (items.length >= 24) break
    }
    if (items.length >= 24) break
  }
  items.sort((a, b) => (levelOrder[a.impactLevel] || 3) - (levelOrder[b.impactLevel] || 3))
  json(res, 200, pageSlice(items, 0, 30))
})
route('POST', '/api/v1/knowledge/questions', async (req, res) => {
  const body = await readBody(req)
  const question = body.question || ''
  const pub = state.policies.filter((p) => p.status === 'PUBLISHED').slice(0, 2)
  const citations = pub.map((p, i) => ({
    order: i + 1, documentName: p.title, version: p.version || 1,
    chunkId: seqId(), chunkIndex: i, source: p.sourceUrl || null, sourceAttachmentId: null,
    sourceFilename: null, quote: p.summary.slice(0, 60), score: 0.92 - i * 0.05,
  }))
  json(res, 200, {
    answer: `针对"${question}"，平台检索到${pub.length}份相关法规与标准。${pub.map((p) => `${p.title}（${p.authority}，${p.publishDate}）就相关要求作出规定：${p.summary.slice(0, 40)}…`).join('')}建议贵单位结合政策影响分析模块查看逐条核对结果。`,
    citations, traceId: seqId(), mode: 'RULES', retrievalMode: 'LEXICAL',
    inputTokens: 96, outputTokens: 142, estimatedCost: 0.0008,
  })
})
route('GET', '/api/v1/notifications/subscriptions', (_req, res) => json(res, 200, state.subscriptions))
route('POST', '/api/v1/notifications/subscriptions', async (req, res) => {
  const body = await readBody(req)
  const item = { id: seqId(), userId: 'u-004', associationId: ASSOCIATION_ID, subscriptionType: body.subscriptionType || 'TENDER', filters: body.filters || {}, channels: body.channels || ['APP'], status: 'ACTIVE', version: 1, createdAt: now(), updatedAt: now() }
  state.subscriptions.push(item)
  json(res, 200, item)
})
route('PUT', '/api/v1/notifications/subscriptions/:id/:action', (req, res, p) => {
  const item = state.subscriptions.find((s) => s.id === p.id)
  if (!item) return error(res, 404, 'NOT_FOUND', '订阅不存在', req.headers['x-request-id'])
  item.status = p.action === 'disable' ? 'DISABLED' : 'ACTIVE'
  item.version += 1
  item.updatedAt = now()
  json(res, 200, item)
})

// ---- 产品与需求 ----
route('GET', '/api/v1/offerings', (req, res) => {
  const q = query(req.url)
  const kw = (q.query || '').trim()
  let list = state.offerings.filter((o) => o.status !== 'DELETED' && !o.disabled)
  if (kw) list = list.filter((o) => `${o.name}${o.enterpriseName}${(o.scenarios || []).join('')}`.includes(kw))
  json(res, 200, pageSlice(list, q.page, q.size))
})
route('POST', '/api/v1/offerings', async (req, res) => {
  const body = await readBody(req)
  const eid = enterpriseOf(req) || body.enterpriseId
  const m = memberById(eid)
  const item = { id: seqId(), enterpriseId: eid, enterpriseName: m?.name || '会员企业', name: body.name, kind: body.kind, description: body.description || null, scenarios: body.scenarios || [], qualifications: body.qualifications || [], visibility: body.visibility || 'ASSOCIATION', status: 'PENDING_REVIEW', version: 1, disabled: false, updatedAt: now() }
  state.offerings.unshift(item)
  json(res, 200, item)
})
route('PUT', '/api/v1/offerings/:id', async (req, res, p) => {
  const item = state.offerings.find((o) => o.id === p.id)
  if (!item) return error(res, 404, 'NOT_FOUND', '产品不存在', req.headers['x-request-id'])
  const body = await readBody(req)
  Object.assign(item, { name: body.name, kind: body.kind, description: body.description, scenarios: body.scenarios, qualifications: body.qualifications, visibility: body.visibility })
  item.version += 1
  item.updatedAt = now()
  json(res, 200, item)
})
route('POST', '/api/v1/offerings/:id/:action', (req, res, p) => {
  const item = state.offerings.find((o) => o.id === p.id)
  if (!item) return error(res, 404, 'NOT_FOUND', '产品不存在', req.headers['x-request-id'])
  if (p.action === 'submit') item.status = 'PENDING_REVIEW'
  if (p.action === 'disable') { item.status = 'DISABLED'; item.disabled = true }
  if (p.action === 'restore') { item.status = 'PUBLISHED'; item.disabled = false }
  item.version += 1
  json(res, 200, item)
})
route('POST', '/api/v1/offerings/:id/review', async (req, res, p) => {
  const item = state.offerings.find((o) => o.id === p.id)
  if (!item) return error(res, 404, 'NOT_FOUND', '产品不存在', req.headers['x-request-id'])
  const body = await readBody(req)
  item.status = body.approved ? 'PUBLISHED' : 'DRAFT'
  item.version += 1
  json(res, 200, item)
})
route('GET', '/api/v1/demands', (req, res) => {
  const q = query(req.url)
  const kw = (q.query || '').trim()
  let list = state.demands.filter((d) => d.status !== 'DELETED' && !d.disabled)
  if (kw) list = list.filter((d) => `${d.title}${d.enterpriseName}${(d.scenarios || []).join('')}`.includes(kw))
  json(res, 200, pageSlice(list, q.page, q.size))
})
route('POST', '/api/v1/demands', async (req, res) => {
  const body = await readBody(req)
  const eid = enterpriseOf(req) || body.enterpriseId
  const m = memberById(eid)
  const item = { id: seqId(), enterpriseId: eid, enterpriseName: m?.name || '会员企业', title: body.title, description: body.description || '', scenarios: body.scenarios || [], requiredCapabilities: body.requiredCapabilities || [], visibility: body.visibility || 'ASSOCIATION', budgetMin: body.budgetMin ?? null, budgetMax: body.budgetMax ?? null, responseDeadline: body.responseDeadline || null, status: 'PENDING_REVIEW', closeReason: null, version: 1, disabled: false, updatedAt: now() }
  state.demands.unshift(item)
  json(res, 200, item)
})
route('PUT', '/api/v1/demands/:id', async (req, res, p) => {
  const item = state.demands.find((d) => d.id === p.id)
  if (!item) return error(res, 404, 'NOT_FOUND', '需求不存在', req.headers['x-request-id'])
  const body = await readBody(req)
  Object.assign(item, { title: body.title, description: body.description, scenarios: body.scenarios, requiredCapabilities: body.requiredCapabilities, visibility: body.visibility, budgetMin: body.budgetMin, budgetMax: body.budgetMax, responseDeadline: body.responseDeadline })
  item.version += 1
  item.updatedAt = now()
  json(res, 200, item)
})
route('POST', '/api/v1/demands/:id/:action', (req, res, p) => {
  const item = state.demands.find((d) => d.id === p.id)
  if (!item) return error(res, 404, 'NOT_FOUND', '需求不存在', req.headers['x-request-id'])
  if (p.action === 'submit') item.status = 'PENDING_REVIEW'
  if (p.action === 'disable') { item.status = 'DISABLED'; item.disabled = true }
  if (p.action === 'restore') { item.status = 'PUBLISHED'; item.disabled = false }
  item.version += 1
  json(res, 200, item)
})
route('POST', '/api/v1/demands/:id/review', async (req, res, p) => {
  const item = state.demands.find((d) => d.id === p.id)
  if (!item) return error(res, 404, 'NOT_FOUND', '需求不存在', req.headers['x-request-id'])
  const body = await readBody(req)
  item.status = body.approved ? 'PUBLISHED' : 'DRAFT'
  item.version += 1
  json(res, 200, item)
})
route('POST', '/api/v1/demands/:id/close', async (req, res, p) => {
  const item = state.demands.find((d) => d.id === p.id)
  if (!item) return error(res, 404, 'NOT_FOUND', '需求不存在', req.headers['x-request-id'])
  const body = await readBody(req)
  item.status = 'CLOSED'
  item.closeReason = body.reason || null
  item.version += 1
  json(res, 200, item)
})

// ---- 生态匹配 ----
route('GET', '/api/v1/matches', (req, res) => {
  const role = roleOf(req)
  const eid = enterpriseOf(req)
  let list = state.matches
  if (isEnterprise(role) && eid) list = list.filter((m) => m.demandEnterpriseId === eid || m.candidateEnterpriseId === eid)
  json(res, 200, list)
})
route('GET', '/api/v1/matches/demand/:demandId', (req, res, p) => json(res, 200, state.matches.filter((m) => m.demandId === p.demandId)))
route('POST', '/api/v1/matches/demand/:demandId/generate', async (req, res, p) => {
  const demand = state.demands.find((d) => d.id === p.demandId)
  if (!demand) return error(res, 404, 'NOT_FOUND', '需求不存在', req.headers['x-request-id'])
  const body = await readBody(req)
  const limit = Math.min(10, body.limit || 10)
  const suppliers = activeMembers()
    .filter((m) => m.id !== demand.enterpriseId)
    .map((m) => ({ m, score: matchScore(m, { title: `${demand.title}${demand.description}`, keywords: [...demand.scenarios, ...demand.requiredCapabilities], category: (demand.scenarios || [])[0] || '' }) }))
    .sort((a, b) => b.score - a.score || a.m.id.localeCompare(b.m.id))
    .slice(0, limit)
    .map(({ m }, i) => ({
      id: seqId(), demandId: demand.id, demandEnterpriseId: demand.enterpriseId, demandCompany: demand.enterpriseName,
      demandTitle: demand.title, scene: (demand.scenarios || [])[0] || '综合', supplierCompany: m.name,
      solution: (m.products || m.capabilities || ['综合服务'])[0], candidateEnterpriseId: m.id,
      score: Math.max(60, 96 - i * 4), reasons: ['应用场景匹配', '供需能力匹配', '技术资质匹配'].slice(0, 2 + (i % 2)),
      state: 'PENDING_CONFIRMATION', recommendedAt: null, demandConfirmedAt: null, candidateConfirmedAt: null,
      closedReason: null, version: 1, updatedAt: now(),
    }))
  state.matches = [...state.matches, ...suppliers]
  json(res, 200, suppliers)
})
route('POST', '/api/v1/matches/:id/:action', async (req, res, p) => {
  const m = state.matches.find((x) => x.id === p.id)
  if (!m) return error(res, 404, 'NOT_FOUND', '匹配不存在', req.headers['x-request-id'])
  const role = roleOf(req)
  const eid = enterpriseOf(req)
  if (p.action === 'recommend') {
    m.state = 'RECOMMENDED'
    m.recommendedAt = m.recommendedAt || now()
  } else if (p.action === 'confirm') {
    const confirmDemand = isAssociationStaff(role) || (eid && eid === m.demandEnterpriseId)
    const confirmCandidate = isAssociationStaff(role) || (eid && eid === m.candidateEnterpriseId)
    if (confirmDemand) m.demandConfirmedAt = m.demandConfirmedAt || now()
    if (confirmCandidate) m.candidateConfirmedAt = m.candidateConfirmedAt || now()
    if (m.demandConfirmedAt && m.candidateConfirmedAt) m.state = 'CONFIRMED'
    else if (m.demandConfirmedAt || m.candidateConfirmedAt) m.state = 'PARTIALLY_CONFIRMED'
  }
  m.version += 1
  m.updatedAt = now()
  json(res, 200, m)
})
route('POST', '/api/v1/matches/:id/close', async (req, res, p) => {
  const m = state.matches.find((x) => x.id === p.id)
  if (!m) return error(res, 404, 'NOT_FOUND', '匹配不存在', req.headers['x-request-id'])
  const body = await readBody(req)
  m.state = 'CLOSED'
  m.closedReason = body.reason || null
  m.version += 1
  json(res, 200, m)
})
route('GET', '/api/v1/matches/:id/invitations', (req, res, p) => json(res, 200, state.invitations.filter((i) => i.matchId === p.id)))
route('POST', '/api/v1/matches/:id/invitations', async (req, res, p) => {
  const body = await readBody(req)
  const m = state.matches.find((x) => x.id === p.id)
  const target = memberById(body.recipientEnterpriseId)
  const item = { id: seqId(), matchId: p.id, senderEnterpriseId: null, recipientEnterpriseId: body.recipientEnterpriseId, invitationType: body.invitationType || 'ASSOCIATION_RECOMMENDATION', status: 'PENDING', message: body.message || null, responseComment: null, sentBySubject: subjectOf(req), respondedBySubject: null, expiresAt: body.expiresAt || null, respondedAt: null, version: 1, createdAt: now(), updatedAt: now() }
  state.invitations.unshift(item)
  if (m) { m.state = 'INVITED'; m.version += 1 }
  json(res, 200, item)
})
route('POST', '/api/v1/matches/invitations/:id/respond', async (req, res, p) => {
  const item = state.invitations.find((i) => i.id === p.id)
  if (!item) return error(res, 404, 'NOT_FOUND', '邀请不存在', req.headers['x-request-id'])
  const body = await readBody(req)
  item.status = body.accepted ? 'ACCEPTED' : 'DECLINED'
  item.responseComment = body.comment || null
  item.respondedBySubject = subjectOf(req)
  item.respondedAt = now()
  item.version += 1
  const m = state.matches.find((x) => x.id === item.matchId)
  if (m && body.accepted) { m.state = 'NEGOTIATING'; m.version += 1 }
  json(res, 200, item)
})
route('GET', '/api/v1/matches/:id/negotiations', (req, res, p) => json(res, 200, state.negotiations.filter((n) => n.matchId === p.id)))
route('POST', '/api/v1/matches/:id/negotiations', async (req, res, p) => {
  const body = await readBody(req)
  const m = state.matches.find((x) => x.id === p.id)
  const item = { id: seqId(), matchId: p.id, enterpriseId: enterpriseOf(req), stage: body.stage || 'INITIAL_CONTACT', summary: body.summary || '', nextAction: body.nextAction || null, nextActionAt: body.nextActionAt || null, recordedBySubject: subjectOf(req), createdAt: now() }
  state.negotiations.unshift(item)
  if (m && m.state === 'CONFIRMED') { m.state = 'NEGOTIATING'; m.version += 1 }
  json(res, 200, item)
})
route('POST', '/api/v1/matches/:id/feedback', async (req, res, p) => {
  const body = await readBody(req)
  const m = state.matches.find((x) => x.id === p.id)
  const item = { id: seqId(), matchId: p.id, enterpriseId: enterpriseOf(req), rating: body.rating ?? null, outcome: body.outcome || 'SUCCESS', closeReason: body.closeReason || null, comment: body.comment || null, submittedBySubject: subjectOf(req), submittedAt: now() }
  state.feedback.unshift(item)
  if (m) { m.state = 'OUTCOME_PENDING'; m.version += 1 }
  json(res, 200, item)
})
route('GET', '/api/v1/matches/:id/feedback', (req, res, p) => json(res, 200, state.feedback.filter((f) => f.matchId === p.id)))
route('GET', '/api/v1/matches/:id/outcomes', (req, res, p) => json(res, 200, state.outcomes.filter((o) => o.matchId === p.id)))
route('POST', '/api/v1/matches/:id/outcomes', async (req, res, p) => {
  const body = await readBody(req)
  const m = state.matches.find((x) => x.id === p.id)
  const item = { id: seqId(), matchId: p.id, title: body.title || '', summary: body.summary || '', contractAmount: body.contractAmount ?? null, resultType: body.resultType || 'COOPERATION', visibility: body.visibility || 'ASSOCIATION', archivedBySubject: subjectOf(req), archivedAt: now(), version: 1 }
  state.outcomes.unshift(item)
  if (m) { m.state = 'ARCHIVED'; m.version += 1 }
  json(res, 200, item)
})

// ---- 协作事项 ----
route('GET', '/api/v1/collaborations/page', (req, res) => {
  const q = query(req.url)
  const kw = (q.query || '').trim()
  const stage = q.stage || ''
  let list = state.collaborations.filter((c) => !c.deleted && c.status !== 'DELETED' && (q.includeDeleted === 'true' || !c.disabled))
  if (stage === 'COMPLETED') list = list.filter((c) => c.stage === 'COMPLETED')
  else if (stage === 'ACTIVE') list = list.filter((c) => !['COMPLETED', 'DISABLED'].includes(c.stage))
  if (kw) list = list.filter((c) => `${c.title}${c.participants.join('')}${c.owner}`.includes(kw))
  json(res, 200, pageSlice(list, q.page, q.size))
})
route('POST', '/api/v1/collaborations', async (req, res) => {
  const body = await readBody(req)
  const role = roleOf(req)
  const item = { id: seqId(), title: body.title, participants: body.participants || [], owner: body.owner || demoUser(req).displayName, stage: role === 'ENTERPRISE_ADMIN' ? 'PROPOSED' : 'OPEN', priority: body.priority || '中', nextAction: body.nextAction || null, dueDate: body.dueDate || null, progress: Number(body.progress) || 0, matchId: body.matchId || null, associationId: ASSOCIATION_ID, enterpriseId: enterpriseOf(req), version: 1, disabled: false, deleted: false, updatedAt: now() }
  state.collaborations.unshift(item)
  json(res, 200, item)
})
route('GET', '/api/v1/collaborations/:id', (req, res, p) => {
  const item = state.collaborations.find((c) => c.id === p.id)
  if (!item) return error(res, 404, 'NOT_FOUND', '协作事项不存在', req.headers['x-request-id'])
  json(res, 200, item)
})
route('PUT', '/api/v1/collaborations/:id', async (req, res, p) => {
  const item = state.collaborations.find((c) => c.id === p.id)
  if (!item) return error(res, 404, 'NOT_FOUND', '协作事项不存在', req.headers['x-request-id'])
  const body = await readBody(req)
  Object.assign(item, { title: body.title, participants: body.participants, owner: body.owner, priority: body.priority, nextAction: body.nextAction, dueDate: body.dueDate, progress: body.progress })
  item.version += 1
  item.updatedAt = now()
  json(res, 200, item)
})
route('POST', '/api/v1/collaborations/:id/submit', (req, res, p) => {
  const item = state.collaborations.find((c) => c.id === p.id)
  if (!item) return error(res, 404, 'NOT_FOUND', '协作事项不存在', req.headers['x-request-id'])
  item.stage = 'PENDING_REVIEW'
  item.version += 1
  json(res, 200, item)
})
route('POST', '/api/v1/collaborations/:id/review', async (req, res, p) => {
  const item = state.collaborations.find((c) => c.id === p.id)
  if (!item) return error(res, 404, 'NOT_FOUND', '协作事项不存在', req.headers['x-request-id'])
  const body = await readBody(req)
  if (body.approved) item.stage = item.enterpriseId ? 'IN_PROGRESS' : 'OPEN'
  else item.stage = 'DRAFT'
  item.version += 1
  json(res, 200, item)
})
route('POST', '/api/v1/collaborations/:id/transition', async (req, res, p) => {
  const item = state.collaborations.find((c) => c.id === p.id)
  if (!item) return error(res, 404, 'NOT_FOUND', '协作事项不存在', req.headers['x-request-id'])
  const body = await readBody(req)
  const target = body.targetStage || ''
  const valid = ['OPEN', 'PROPOSED', 'IN_PROGRESS', 'PENDING_REVIEW', 'COMPLETED', 'TERMINATED']
  if (valid.includes(target)) { item.stage = target; item.progress = Math.min(100, (item.progress || 0) + 15) }
  state.collaborationActivities.unshift({ id: nextSeq(), type: '阶段变更', detail: body.detail || `进入${target}阶段`, actorSubject: subjectOf(req), occurredAt: now() })
  item.version += 1
  item.updatedAt = now()
  json(res, 200, item)
})
route('GET', '/api/v1/collaborations/:id/activities', (req, res, p) => json(res, 200, state.collaborationActivities.filter((a) => true).slice(0, 20)))
route('POST', '/api/v1/collaborations/:id/activities', async (req, res, p) => {
  const body = await readBody(req)
  const item = { id: nextSeq(), type: body.type || '备注', detail: body.detail || '', actorSubject: subjectOf(req), occurredAt: now() }
  state.collaborationActivities.unshift(item)
  json(res, 200, item)
})

// ---- 资料附件 ----
route('GET', '/api/v1/attachments', (req, res) => {
  const q = query(req.url)
  let list = state.attachments.filter((a) => (q.includeDeleted === 'true' || !a.deletedAt))
  if (q.enterpriseId) list = list.filter((a) => a.enterpriseId === q.enterpriseId)
  json(res, 200, { ...pageSlice(list, q.page, q.size), items: list.map((a) => ({ ...a })) })
})
route('POST', '/api/v1/attachments', async (req, res) => {
  const form = await readBody(req)
  const file = form.get('file')
  const q = query(req.url)
  const item = { id: seqId(), associationId: ASSOCIATION_ID, enterpriseId: q.enterpriseId || null, originalFilename: file?.name || '上传文件', mediaType: file?.type || 'application/octet-stream', sizeBytes: file?.size || 1024, sha256: `d${'1'.repeat(63)}`, scanStatus: 'CLEAN', visibility: q.visibility || 'PRIVATE', status: 'ACTIVE', version: 1, uploadedAt: now(), updatedAt: now(), deletedAt: null }
  state.attachments.unshift(item)
  json(res, 200, item)
})
route('GET', '/api/v1/attachments/:id/content', (req, res, p) => {
  const item = state.attachments.find((a) => a.id === p.id)
  if (!item) return error(res, 404, 'NOT_FOUND', '附件不存在', req.headers['x-request-id'])
  const body = Buffer.from(`（本地演示内容占位）${item.originalFilename}\n文件大小：${item.sizeBytes} 字节`, 'utf8')
  res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Length': body.length })
  res.end(body)
})
route('DELETE', '/api/v1/attachments/:id', (req, res, p) => {
  const item = state.attachments.find((a) => a.id === p.id)
  if (!item) return error(res, 404, 'NOT_FOUND', '附件不存在', req.headers['x-request-id'])
  item.deletedAt = now()
  item.version += 1
  json(res, 200, item)
})
route('PUT', '/api/v1/attachments/:id/restore', (req, res, p) => {
  const item = state.attachments.find((a) => a.id === p.id)
  if (!item) return error(res, 404, 'NOT_FOUND', '附件不存在', req.headers['x-request-id'])
  item.deletedAt = null
  item.version += 1
  json(res, 200, item)
})
route('POST', '/api/v1/knowledge/documents/file', async (req, res) => {
  const body = await readBody(req)
  json(res, 200, { documentId: seqId(), documentVersionId: seqId(), version: 1, chunkCount: 6, contentHash: `h${'9'.repeat(63)}`, embeddingProvider: null, embeddingModel: null, embeddingDimensions: 0 })
})

// ---- 友好协会 ----
route('GET', '/api/v1/cross-associations/access-requests', (_req, res) => json(res, 200, state.accessRequests))
route('POST', '/api/v1/cross-associations/access-requests', async (req, res) => {
  const body = await readBody(req)
  const item = { id: seqId(), applicantAssociationId: ASSOCIATION_ID, applicantAssociationName: '北京地下管线协会', targetAssociationId: body.targetAssociationId || uuid(778), reason: body.reason || null, status: 'PENDING', requestedBySubject: subjectOf(req), reviewedBySubject: null, reviewComment: null, requestedAt: now(), reviewedAt: null }
  state.accessRequests.unshift(item)
  json(res, 200, item)
})
route('PUT', '/api/v1/cross-associations/access-requests/:id/review', async (req, res, p) => {
  const item = state.accessRequests.find((a) => a.id === p.id)
  if (!item) return error(res, 404, 'NOT_FOUND', '接入申请不存在', req.headers['x-request-id'])
  const body = await readBody(req)
  item.status = body.decision === 'APPROVE' ? 'APPROVED' : 'REJECTED'
  item.reviewedBySubject = subjectOf(req)
  item.reviewComment = body.comment || null
  item.reviewedAt = now()
  const rel = state.relationships.find((r) => r.targetAssociationId === item.applicantAssociationId)
  if (rel && body.decision === 'APPROVE') { rel.status = 'ACTIVE'; rel.allowMemberData = Boolean(body.allowMemberData); rel.version += 1 }
  json(res, 200, item)
})
route('GET', '/api/v1/cross-associations/relationships', (_req, res) => json(res, 200, state.relationships.map((r) => ({ ...r, id: r.id || `${r.sourceAssociationId}-${r.targetAssociationId}` }))))
route('PUT', '/api/v1/cross-associations/relationships/:source/:target', async (req, res, p) => {
  const item = state.relationships.find((r) => r.sourceAssociationId === p.source && r.targetAssociationId === p.target)
  if (!item) return error(res, 404, 'NOT_FOUND', '关系不存在', req.headers['x-request-id'])
  const body = await readBody(req)
  if (body.action === 'ACTIVATE') item.status = 'ACTIVE'
  if (body.action === 'SUSPEND') item.status = 'SUSPENDED'
  if (body.action === 'REVOKE') item.status = 'REVOKED'
  item.version += 1
  item.updatedAt = now()
  json(res, 200, item)
})

// ---- 通知消息 ----
route('GET', '/api/v1/notifications/messages', (req, res) => {
  const q = query(req.url)
  let list = state.notifications
  if (q.unreadOnly === 'true') list = list.filter((n) => !n.readAt)
  const page = pageSlice(list, q.page, q.size)
  json(res, 200, { ...page, items: page.items })
})
route('PUT', '/api/v1/notifications/messages/:id/read', (req, res, p) => {
  const item = state.notifications.find((n) => n.id === p.id)
  if (!item) return error(res, 404, 'NOT_FOUND', '通知不存在', req.headers['x-request-id'])
  item.readAt = item.readAt || now()
  item.status = 'READ'
  json(res, 200, item)
})

// ---- 工作台 ----
route('GET', '/api/v1/dashboards/association', (_req, res) => {
  const active = activeMembers()
  const sceneCount = new Map()
  for (const m of active) for (const s of m.scenes || []) sceneCount.set(s, (sceneCount.get(s) || 0) + 1)
  const sceneTotal = [...sceneCount.values()].reduce((a, b) => a + b, 0) || 1
  const sceneDistribution = [...sceneCount.entries()]
    .map(([name, count]) => ({ name, count, percent: Math.round((count / sceneTotal) * 1000) / 10 }))
    .sort((a, b) => b.count - a.count).slice(0, 8)
  const pending = state.collaborations.filter((c) => !['COMPLETED', 'DISABLED'].includes(c.stage)).slice(0, 6)
  const tendersActive = state.tenders.filter((t) => t.status === 'ACTIVE').length
  const activities = [
    { id: seqId(), title: '招标信息推送', detail: `协会向 ${state.tenderPushes.length} 家会员推送了相关招标信息`, time: isoDate(2026, 8, 26, 10), type: 'task' },
    { id: seqId(), title: '新增政策', detail: '《北京市地下管线管理办法》已收录并完成影响分析', time: isoDate(2026, 8, 25, 9), type: 'policy' },
    { id: seqId(), title: '生态匹配', detail: '回龙观供热改造需求推荐了 3 家候选服务商', time: isoDate(2026, 8, 24, 15), type: 'match' },
    { id: seqId(), title: '会员建档', detail: `${active.length} 家会员已完成资料归档`, time: isoDate(2026, 8, 23, 11), type: 'member' },
    { id: seqId(), title: '协作事项', detail: '服贸会联合展示筹备进入物料确认阶段', time: isoDate(2026, 8, 22, 17), type: 'collaboration' },
    { id: seqId(), title: '试点数据收集', detail: '回天地区网格服务覆盖数据收集持续推进', time: isoDate(2026, 8, 20, 14), type: 'task' },
  ]
  json(res, 200, {
    metrics: [
      { label: '会员企业', value: String(state.members.filter((m) => m.status !== 'DELETED').length), change: '覆盖北京市 16 区', tone: 'success' },
      { label: '政策标准', value: String(state.policies.length), change: '含国家与北京市两级', tone: 'success' },
      { label: '招标信息', value: String(state.tenders.length), change: `${tendersActive} 条正在招标`, tone: 'warning' },
      { label: '待推进协作', value: String(pending.length), change: '含服贸会筹备', tone: 'info' },
    ],
    activities, sceneDistribution, pendingTasks: pending,
  })
})
route('GET', '/api/v1/dashboards/enterprise', (req, res) => {
  const eid = enterpriseOf(req)
  const member = memberById(eid || ENTERPRISE_ID)
  const mine = relevantTenders(member).slice(0, 3)
  const mineMatches = state.matches.filter((m) => m.demandEnterpriseId === eid || m.candidateEnterpriseId === eid).slice(0, 3)
  const pols = state.policies.filter((p) => p.status === 'PUBLISHED').slice(0, 4)
  const todo = state.collaborations.filter((c) => !['COMPLETED', 'DISABLED'].includes(c.stage)).slice(0, 3)
  json(res, 200, {
    completeness: member?.completeness ?? 70,
    metrics: [
      { label: '相关招标', value: String(mine.length), change: '系统按能力自动匹配', tone: 'success' },
      { label: '商机匹配', value: String(mineMatches.length), change: '含 1 条待确认', tone: 'info' },
      { label: '协作事项', value: String(todo.length), change: '1 项本周到期', tone: 'warning' },
      { label: '政策提醒', value: String(pols.length), change: '已按业务筛选', tone: 'success' },
    ],
    recommendedPolicies: pols.map((p) => ({ ...p, version: p.version || 1 })),
    matches: mineMatches.map((m) => ({ id: m.id, demandCompany: m.demandCompany, demandTitle: m.demandTitle, scene: m.scene, supplierCompany: m.supplierCompany, solution: m.solution, score: m.score, reasons: m.reasons, state: m.state, updatedAt: m.updatedAt })),
    todo,
  })
})

// ---- 招标信息（新增功能）----
route('GET', '/api/v1/tenders/mine', (req, res) => {
  const q = query(req.url)
  const role = roleOf(req)
  const eid = enterpriseOf(req)
  const member = memberById(eid || '')
  if (!isEnterprise(role) || !member) return json(res, 200, pageSlice([], q.page, q.size))
  const rel = relevantTenders(member)
  const page = pageSlice(rel.map((r) => tenderView(r.item, role, eid)), q.page, q.size)
  json(res, 200, page)
})
route('GET', '/api/v1/tenders', (req, res) => {
  const q = query(req.url)
  const role = roleOf(req)
  const eid = enterpriseOf(req)
  const kw = (q.query || '').trim()
  let list = state.tenders.slice()
  if (q.category) list = list.filter((t) => t.category === q.category)
  if (q.region) list = list.filter((t) => t.region === q.region)
  if (q.status === 'ACTIVE') list = list.filter((t) => t.status === 'ACTIVE')
  if (q.status === 'CLOSED') list = list.filter((t) => t.status === 'CLOSED')
  if (kw) list = list.filter((t) => `${t.title}${t.purchaser}${(t.keywords || []).join('')}`.includes(kw))
  list.sort((a, b) => String(b.publishDate).localeCompare(String(a.publishDate)))
  const page = pageSlice(list, q.page, q.size)
  json(res, 200, { ...page, items: page.items.map((t) => tenderView(t, role, eid)) })
})
route('POST', '/api/v1/tenders', async (req, res) => {
  const body = await readBody(req)
  const item = {
    id: seqId(), associationId: ASSOCIATION_ID, title: body.title, purchaser: body.purchaser || '',
    agency: body.agency || null, region: body.region || '北京市', category: body.category || '其他',
    keywords: body.keywords || [], budget: Number(body.budget) || 0, publishDate: body.publishDate || now().slice(0, 10),
    deadline: body.deadline || null, source: body.source || '北京市公共资源交易服务平台',
    sourceUrl: body.sourceUrl || null, status: body.status === 'CLOSED' ? 'CLOSED' : 'ACTIVE',
    version: 1, createdAt: now(), updatedAt: now(),
  }
  state.tenders.unshift(item)
  json(res, 200, item)
})
route('POST', '/api/v1/tenders/batch', async (req, res) => {
  const body = await readBody(req)
  const rows = Array.isArray(body) ? body : (body.items || [])
  const created = rows.map((b) => {
    const item = { id: seqId(), associationId: ASSOCIATION_ID, title: b.title, purchaser: b.purchaser || '', agency: b.agency || null, region: b.region || '北京市', category: b.category || '其他', keywords: b.keywords || [], budget: Number(b.budget) || 0, publishDate: b.publishDate, deadline: b.deadline || null, source: b.source || '北京市公共资源交易服务平台', sourceUrl: b.sourceUrl || null, status: 'ACTIVE', version: 1, createdAt: now(), updatedAt: now() }
    state.tenders.unshift(item)
    return item
  })
  json(res, 200, created)
})
route('POST', '/api/v1/tenders/:id/push', async (req, res, p) => {
  const tender = state.tenders.find((t) => t.id === p.id)
  if (!tender) return error(res, 404, 'NOT_FOUND', '招标信息不存在', req.headers['x-request-id'])
  const body = await readBody(req)
  let targets = activeMembers()
  if (Array.isArray(body.enterpriseIds) && body.enterpriseIds.length) targets = targets.filter((m) => body.enterpriseIds.includes(m.id))
  const created = []
  for (const m of targets) {
    const exists = state.tenderPushes.some((x) => x.tenderId === tender.id && x.enterpriseId === m.id)
    if (exists) continue
    const item = { id: seqId(), tenderId: tender.id, tenderTitle: tender.title, enterpriseId: m.id, enterpriseName: m.name, pushedBySubject: subjectOf(req), pushedAt: now(), status: 'PUSHED', version: 1 }
    state.tenderPushes.push(item)
    created.push(item)
    state.notifications.unshift({ id: seqId(), notificationType: 'TENDER', title: `招标推送：${tender.title}`, body: `${m.name}，协会为您推送了与您能力相关的招标信息（${tender.category}，预算 ${(tender.budget / 10000).toFixed(0)} 万元），请在招标信息页查看并准备响应。`, resourceType: 'TENDER', resourceId: tender.id, status: 'UNREAD', readAt: null, createdAt: now(), deliveredAt: now() })
  }
  json(res, 200, created)
})
route('GET', '/api/v1/tenders/:id/pushes', (req, res, p) => {
  json(res, 200, state.tenderPushes.filter((x) => x.tenderId === p.id))
})

// ------------------------------------------------------------------ 分发 ----
const server = http.createServer(async (req, res) => {
  const requestId = req.headers['x-request-id'] || `srv-${Date.now().toString(36)}`
  const cors = {
    'Access-Control-Allow-Origin': req.headers.origin || '*',
    'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE,OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type,If-Match,ETag,X-Request-Id,X-Guanxian-Demo-Role,X-Guanxian-Association-Id,X-Guanxian-Enterprise-Id',
    'Access-Control-Expose-Headers': 'ETag,X-Request-Id,Content-Disposition',
    'X-Request-Id': requestId,
  }
  // 所有响应统一带跨域与请求追踪头
  for (const [name, value] of Object.entries(cors)) res.setHeader(name, value)
  if (req.method === 'OPTIONS') {
    res.writeHead(204)
    return res.end()
  }
  const pathOnly = req.url.split('?')[0]
  try {
    for (const r of router) {
      if (r.method !== req.method) continue
      const match = pathOnly.match(r.regex)
      if (!match) continue
      await r.handler(req, res, match)
      return
    }
    return json(res, 404, { code: 'NOT_FOUND', message: `接口不存在：${req.method} ${pathOnly}` })
  } catch (err) {
    const status = err.status || 500
    console.error(`[demo] ${req.method} ${pathOnly} -> ${status}`, err && (err.stack || err.message) || err)
    return json(res, status, { code: err.code || 'INTERNAL_ERROR', message: status === 413 ? '请求体过大' : '服务处理失败' })
  }
})

server.listen(PORT, () => {
  console.log(`\n  管线智联 · 本地演示服务已启动`)
  console.log(`  地址： http://localhost:${PORT}/api/v1/health`)
  console.log(`  数据： ${state.members.length} 家会员 / ${state.policies.length} 条政策 / ${state.tenders.length} 条招标\n`)
})

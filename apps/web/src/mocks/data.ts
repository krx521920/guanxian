import type {
  Collaboration,
  DashboardData,
  EcosystemMatch,
  EnterpriseDashboardData,
  MemberEnterprise,
  Policy,
} from '../types/domain'

export const members: MemberEnterprise[] = [
  { id: 'E001', name: '北京市政建设集团有限责任公司', shortName: '北京建工市政', role: '建设施工单位', scenes: ['施工建设', '更新改造'], products: ['地下管线施工', '非开挖修复'], city: '北京', contact: '陈工', completeness: 96, status: '已认证', visibility: 'MEMBERS', canEdit: true, canReview: true, updatedAt: '2026-08-13' },
  { id: 'E002', name: '京城管网科技有限公司', shortName: '京城管网', role: '技术服务单位', scenes: ['探测测绘', '运行监测'], products: ['管网数字孪生', '泄漏监测平台'], city: '北京', contact: '王志远', completeness: 91, status: '已认证', visibility: 'MEMBERS', canEdit: true, canReview: true, updatedAt: '2026-08-12' },
  { id: 'E003', name: '华北智慧阀门制造有限公司', shortName: '华北阀门', role: '产品制造单位', scenes: ['运行维护', '应急处置'], products: ['智能球阀', '远程控制器'], city: '廊坊', contact: '周经理', completeness: 78, status: '待完善', visibility: 'MEMBERS', canEdit: true, canReview: true, updatedAt: '2026-08-11' },
  { id: 'E004', name: '中勘地下空间技术研究院', shortName: '中勘研究院', role: '勘察设计单位', scenes: ['规划设计', '探测测绘'], products: ['地质勘察', '管线综合设计'], city: '北京', contact: '刘工', completeness: 85, status: '待审核', visibility: 'MEMBERS', canEdit: true, canReview: true, updatedAt: '2026-08-10' },
  { id: 'E005', name: '北方燃气安全科技股份有限公司', shortName: '北方燃气安全', role: '安全设备单位', scenes: ['运行监测', '应急处置'], products: ['燃气报警器', '巡检机器人'], city: '天津', contact: '韩经理', completeness: 93, status: '已认证', visibility: 'MEMBERS', canEdit: true, canReview: true, updatedAt: '2026-08-09' },
  { id: 'E006', name: '首都城市更新发展有限公司', shortName: '首都更新', role: '建设运营单位', scenes: ['更新改造', '运营管理'], products: ['更新项目管理', '城市体检服务'], city: '北京', contact: '宋主任', completeness: 69, status: '待完善', visibility: 'MEMBERS', canEdit: true, canReview: true, updatedAt: '2026-08-08' },
]

export const policies: Policy[] = [
  { id: 'P001', title: '城市地下管线建设管理工作指导意见', authority: '住房和城乡建设部', level: '国家', category: '建设管理', publishDate: '2026-08-01', effectiveDate: '2026-09-01', status: '即将施行', summary: '强化地下管线全生命周期管理，推动数字化交付与风险分级管控。', tags: ['全生命周期', '数字化交付', '风险管理'] },
  { id: 'P002', title: '北京市地下管线信息管理办法（修订）', authority: '北京市城市管理委员会', level: '北京市', category: '信息管理', publishDate: '2026-07-20', effectiveDate: '2026-08-20', status: '即将施行', summary: '明确管线信息汇交、更新与共享要求，细化建设单位和权属单位责任。', tags: ['信息汇交', '数据标准', '权属责任'] },
  { id: 'P003', title: '城镇燃气管网泄漏监测技术导则', authority: '中国城市燃气协会', level: '行业协会', category: '安全运行', publishDate: '2026-06-16', effectiveDate: '2026-06-16', status: '现行有效', summary: '规定燃气管网泄漏监测系统的建设、运行和数据评价要求。', tags: ['燃气', '泄漏监测', '安全'] },
  { id: 'P004', title: '地下管线非开挖修复工程评价标准（征求意见稿）', authority: '北京地下管线协会', level: '行业协会', category: '更新改造', publishDate: '2026-08-08', effectiveDate: '—', status: '征求意见', summary: '建立非开挖修复项目的技术、质量与成效评价指标体系。', tags: ['非开挖', '修复', '质量评价'] },
]

export const matches: EcosystemMatch[] = [
  { id: 'M001', demandCompany: '北京市政建设集团', demandTitle: '高压燃气管道零泄漏阀门采购', scene: '燃气管网 · 更新改造', supplierCompany: '华北智慧阀门制造有限公司', solution: '智能零泄漏球阀及远程控制方案', score: 94, reasons: ['介质与压力等级匹配', '具有同类燃气项目案例', '北京周边可快速交付'], state: '沟通中', updatedAt: '今天 10:30' },
  { id: 'M002', demandCompany: '首都城市更新发展有限公司', demandTitle: '老旧街区地下管线综合探测', scene: '城市更新 · 探测测绘', supplierCompany: '中勘地下空间技术研究院', solution: '多源探测与三维管线建模服务', score: 91, reasons: ['城市更新场景匹配', '具备测绘甲级资质', '服务覆盖北京地区'], state: '已推荐', updatedAt: '昨天 16:18' },
  { id: 'M003', demandCompany: '北方燃气安全科技', demandTitle: '管网监测平台数字孪生能力合作', scene: '燃气管网 · 运行监测', supplierCompany: '京城管网科技有限公司', solution: '监测数据治理与数字孪生底座', score: 88, reasons: ['能力互补度高', '接口技术栈兼容', '有联合投标意向'], state: '待确认', updatedAt: '昨天 09:40' },
  { id: 'M004', demandCompany: '京城管网科技有限公司', demandTitle: '巡检机器人燃气传感器集成', scene: '运行维护 · 智能巡检', supplierCompany: '北方燃气安全科技股份有限公司', solution: '复合气体传感与边缘告警模组', score: 86, reasons: ['产品接口可适配', '应用场景高度一致', '双方均有联合研发诉求'], state: '已达成', updatedAt: '08-10 14:25' },
]

export const collaborations: Collaboration[] = [
  { id: 'C001', title: '高压燃气管道零泄漏阀门联合评估', participants: ['北京市政建设集团', '华北智慧阀门'], owner: '徐明', stage: '联合评估', priority: '高', nextAction: '确认试验场地与技术参数', dueDate: '2026-08-18', progress: 62 },
  { id: 'C002', title: '老旧街区地下管线综合探测需求对接', participants: ['首都城市更新', '中勘研究院'], owner: '陈晓', stage: '方案沟通', priority: '中', nextAction: '上传初步勘察方案', dueDate: '2026-08-21', progress: 38 },
  { id: 'C003', title: '监测平台与数字孪生底座联合方案', participants: ['北方燃气安全', '京城管网'], owner: '王志远', stage: '待受理', priority: '中', nextAction: '确认双方技术联系人', dueDate: '2026-08-23', progress: 16 },
  { id: 'C004', title: '非开挖修复评价标准案例征集', participants: ['北京地下管线协会', '北京建工市政'], owner: '张全超', stage: '已完成', priority: '低', nextAction: '归档评审意见', dueDate: '2026-08-12', progress: 100 },
]

export const associationDashboard: DashboardData = {
  metrics: [
    { label: '会员企业', value: '106', change: '本月新增 4 家', tone: 'info' },
    { label: '企业资料完整度', value: '82.6%', change: '较上月 +6.2%', tone: 'success' },
    { label: '本月有效匹配', value: '38', change: '其中 12 项沟通中', tone: 'warning' },
    { label: '待办协作事项', value: '9', change: '3 项将在本周到期', tone: 'danger' },
  ],
  activities: [
    { id: 'A1', title: 'AI 生成 6 组生态匹配建议', detail: '涉及燃气安全、管线探测和非开挖修复场景', time: '18 分钟前', type: 'match' },
    { id: 'A2', title: '北京市地下管线信息管理办法更新', detail: '已识别 4 类会员企业可能受到影响', time: '1 小时前', type: 'policy' },
    { id: 'A3', title: '中勘地下空间技术研究院提交认证', detail: '企业资料完整度 85%，等待协会审核', time: '昨天 16:42', type: 'member' },
    { id: 'A4', title: '零泄漏阀门联合评估进入新阶段', detail: '下一步需协会协调试验场地', time: '昨天 10:20', type: 'task' },
  ],
  sceneDistribution: [
    { name: '规划设计', count: 18, percent: 64 },
    { name: '施工建设', count: 27, percent: 82 },
    { name: '运行监测', count: 32, percent: 96 },
    { name: '更新改造', count: 21, percent: 71 },
    { name: '应急处置', count: 16, percent: 58 },
  ],
  pendingTasks: collaborations.slice(0, 3),
}

export const enterpriseDashboard: EnterpriseDashboardData = {
  completeness: 91,
  metrics: [
    { label: '在架产品/服务', value: '12', change: '2 项待补充技术参数', tone: 'info' },
    { label: '匹配商机', value: '8', change: '近 30 天新增 5 项', tone: 'success' },
    { label: '协作进行中', value: '3', change: '1 项等待我方反馈', tone: 'warning' },
    { label: '政策影响提醒', value: '4', change: '含 1 项即将施行', tone: 'danger' },
  ],
  recommendedPolicies: policies.slice(0, 3),
  matches: [matches[2], matches[3]],
  todo: [collaborations[2], collaborations[0]],
}

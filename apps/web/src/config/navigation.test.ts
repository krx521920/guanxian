import { describe, expect, it } from 'vitest'
import type { UserRole } from '../types/domain'
import { allRoles as configuredAllRoles, associationRoles as configuredAssociationRoles, defaultRouteForRole, enterpriseRoles as configuredEnterpriseRoles, hasAnyRole, observerRoles as configuredObserverRoles, roleDescriptions, roleLabels, workspaceRoles as configuredWorkspaceRoles } from './roles'
import { navigation, navigationForRole } from './navigation'

const associationRoles: UserRole[] = ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR']
const enterpriseRoles: UserRole[] = ['ENTERPRISE_ADMIN', 'ENTERPRISE_MEMBER']
const observerRoles: UserRole[] = ['OBSERVER']

describe('role navigation', () => {
  it('lets enterprise members view the scoped member directory without the association workspace', () => {
    const paths = navigationForRole('ENTERPRISE_MEMBER').map((item) => item.to)
    expect(paths).toContain('/enterprise')
    expect(paths).not.toContain('/association')
    expect(paths).toContain('/members')
  })

  it('allows association operators to manage members and collaboration', () => {
    const paths = navigationForRole('ASSOCIATION_OPERATOR').map((item) => item.to)
    expect(paths).toEqual(expect.arrayContaining(['/association', '/members', '/policies', '/matching', '/collaborations']))
  })

  it('selects the correct landing page for each workspace', () => {
    associationRoles.forEach((role) => expect(defaultRouteForRole(role)).toBe('/association'))
    enterpriseRoles.forEach((role) => expect(defaultRouteForRole(role)).toBe('/enterprise'))
    observerRoles.forEach((role) => expect(defaultRouteForRole(role)).toBe('/members'))
  })

  it.each(associationRoles)('shows association-only navigation to %s', (role) => {
    const paths = navigationForRole(role).map((item) => item.to)
    expect(paths).toEqual(expect.arrayContaining(['/association', '/members']))
    expect(paths).not.toContain('/enterprise')
  })

  it.each(enterpriseRoles)('shows the scoped member directory but not association administration to %s', (role) => {
    const paths = navigationForRole(role).map((item) => item.to)
    expect(paths).toEqual(expect.arrayContaining(['/enterprise', '/policies', '/matching', '/collaborations']))
    expect(paths).not.toContain('/association')
    expect(paths).toContain('/members')
  })

  it.each([...associationRoles, ...enterpriseRoles])('shows shared ecosystem navigation to %s', (role) => {
    const paths = navigationForRole(role).map((item) => item.to)
    expect(paths).toEqual(expect.arrayContaining(['/policies', '/ecosystem/overview', '/ecosystem', '/matching', '/collaborations', '/attachments']))
  })

  it('limits observers to member and policy read-only entry points', () => {
    expect(navigationForRole('OBSERVER').map((item) => item.to)).toEqual(['/members', '/policies'])
  })

  it('denies access by default when a protected route omits role constraints', () => {
    expect(hasAnyRole('ENTERPRISE_MEMBER')).toBe(false)
    expect(hasAnyRole('ENTERPRISE_MEMBER', [])).toBe(false)
    expect(hasAnyRole('ASSOCIATION_ADMIN', ['ASSOCIATION_ADMIN'])).toBe(true)
    expect(hasAnyRole('ENTERPRISE_ADMIN', associationRoles)).toBe(false)
  })

  it('does not emit duplicate navigation destinations for any role', () => {
    ;[...associationRoles, ...enterpriseRoles, ...observerRoles].forEach((role) => {
      const paths = navigationForRole(role).map((item) => item.to)
      expect(new Set(paths).size).toBe(paths.length)
    })
  })

  it('keeps role metadata complete and aligned with workspace groups', () => {
    expect(configuredAssociationRoles).toEqual(associationRoles)
    expect(configuredEnterpriseRoles).toEqual(enterpriseRoles)
    expect(configuredObserverRoles).toEqual(observerRoles)
    expect(configuredWorkspaceRoles).toEqual([...associationRoles, ...enterpriseRoles])
    expect(configuredAllRoles).toEqual([...associationRoles, ...enterpriseRoles, ...observerRoles])
    expect(roleLabels).toEqual({
      SYSTEM_ADMIN: '系统管理员',
      ASSOCIATION_ADMIN: '协会管理员',
      ASSOCIATION_OPERATOR: '协会运营人员',
      ENTERPRISE_ADMIN: '企业管理员',
      ENTERPRISE_MEMBER: '企业业务人员',
      OBSERVER: '只读观察员',
    })
    expect(roleDescriptions).toEqual({
      SYSTEM_ADMIN: '平台配置、全局数据与安全审计',
      ASSOCIATION_ADMIN: '协会运营、会员审核与生态统筹',
      ASSOCIATION_OPERATOR: '内容维护、撮合跟进与任务办理',
      ENTERPRISE_ADMIN: '企业资料、团队与供需信息管理',
      ENTERPRISE_MEMBER: '查看企业信息、政策、匹配与协作进度',
      OBSERVER: '只读查看会员资料与政策标准',
    })
  })

  it('keeps navigation labels, icons and badges stable for the application shell', () => {
    expect(navigation.map(({ label, to, icon, badge }) => ({ label, to, icon, badge }))).toEqual([
      { label: '协会工作台', to: '/association', icon: 'dashboard', badge: undefined },
      { label: '企业工作台', to: '/enterprise', icon: 'dashboard', badge: undefined },
      { label: '会员企业', to: '/members', icon: 'enterprise', badge: undefined },
      { label: '政策标准', to: '/policies', icon: 'policy', badge: undefined },
      { label: '生态全景', to: '/ecosystem/overview', icon: 'ecosystem', badge: undefined },
      { label: '产品与需求', to: '/ecosystem', icon: 'ecosystem', badge: undefined },
      { label: '生态匹配', to: '/matching', icon: 'match', badge: undefined },
      { label: '协作事项', to: '/collaborations', icon: 'task', badge: undefined },
      { label: '资料附件', to: '/attachments', icon: 'enterprise', badge: undefined },
      { label: '友好协会', to: '/federation', icon: 'match', badge: undefined },
      { label: '审计与账号', to: '/operations', icon: 'task', badge: undefined },
    ])
  })
})

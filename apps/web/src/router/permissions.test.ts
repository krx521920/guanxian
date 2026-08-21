import { describe, expect, it } from 'vitest'
import { ROLES } from '../types/domain'
import { protectedRouteRoles } from './permissions'

describe('protected route permissions', () => {
  it('declares an explicit non-empty role list for every protected page', () => {
    expect(Object.keys(protectedRouteRoles).sort()).toEqual([
      '/association',
      '/collaborations',
      '/enterprise',
      '/matching',
      '/members',
      '/members/edit',
      '/members/new',
      '/policies',
    ])
    Object.values(protectedRouteRoles).forEach((roles) => expect(roles.length).toBeGreaterThan(0))
  })

  it('contains only known roles', () => {
    Object.values(protectedRouteRoles).flat().forEach((role) => expect(ROLES).toContain(role))
  })

  it('separates association and enterprise workspaces while sharing ecosystem pages', () => {
    expect(protectedRouteRoles['/association']).not.toContain('ENTERPRISE_MEMBER')
    expect(protectedRouteRoles['/enterprise']).not.toContain('ASSOCIATION_OPERATOR')
    expect(protectedRouteRoles['/members']).toEqual([...ROLES])
    expect(protectedRouteRoles['/members/edit']).toContain('ENTERPRISE_ADMIN')
    expect(protectedRouteRoles['/members/edit']).not.toContain('ENTERPRISE_MEMBER')
    expect(protectedRouteRoles['/members/new']).not.toContain('ENTERPRISE_ADMIN')
    expect(protectedRouteRoles['/policies']).toEqual([...ROLES])
    expect(protectedRouteRoles['/matching']).toEqual([...ROLES])
    expect(protectedRouteRoles['/collaborations']).toEqual([...ROLES])
  })
})

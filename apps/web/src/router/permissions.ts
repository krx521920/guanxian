import { allRoles, associationRoles, enterpriseRoles } from '../config/roles'
import type { UserRole } from '../types/domain'

export const protectedRouteRoles: Record<string, readonly UserRole[]> = {
  '/association': associationRoles,
  '/enterprise': enterpriseRoles,
  '/ecosystem': allRoles,
  '/members': allRoles,
  '/members/edit': [...associationRoles, 'ENTERPRISE_ADMIN'],
  '/members/new': associationRoles,
  '/policies': allRoles,
  '/matching': allRoles,
  '/collaborations': allRoles,
}

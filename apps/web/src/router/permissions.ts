import { allRoles, associationRoles, enterpriseRoles } from '../config/roles'
import type { UserRole } from '../types/domain'

export const protectedRouteRoles: Record<string, readonly UserRole[]> = {
  '/association': associationRoles,
  '/enterprise': enterpriseRoles,
  '/members': allRoles,
  '/members/edit': [...associationRoles, 'ENTERPRISE_ADMIN'],
  '/members/new': associationRoles,
  '/policies': allRoles,
  '/ecosystem': allRoles,
  '/matching': allRoles,
  '/collaborations': allRoles,
  '/attachments': allRoles,
  '/federation': associationRoles,
}

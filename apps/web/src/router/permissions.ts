import { allRoles, associationRoles, enterpriseRoles, workspaceRoles } from '../config/roles'
import type { UserRole } from '../types/domain'

export const protectedRouteRoles: Record<string, readonly UserRole[]> = {
  '/association': associationRoles,
  '/enterprise': enterpriseRoles,
  '/members': allRoles,
  '/members/edit': [...associationRoles, 'ENTERPRISE_ADMIN'],
  '/members/new': associationRoles,
  '/policies': allRoles,
  '/ecosystem/overview': workspaceRoles,
  '/ecosystem': workspaceRoles,
  '/matching': workspaceRoles,
  '/collaborations': workspaceRoles,
  '/attachments': workspaceRoles,
  '/federation': associationRoles,
  '/operations': ['SYSTEM_ADMIN', 'ASSOCIATION_ADMIN'],
}

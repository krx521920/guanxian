import { allRoles, associationRoles, enterpriseRoles } from '../config/roles'
import type { UserRole } from '../types/domain'

export const protectedRouteRoles: Record<string, readonly UserRole[]> = {
  '/association': associationRoles,
  '/enterprise': enterpriseRoles,
  '/members': associationRoles,
  '/policies': allRoles,
  '/matching': allRoles,
  '/collaborations': allRoles,
}

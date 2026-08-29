import type { UserRole } from '../types/domain'

let accessToken: string | null = null
let demoRole: UserRole | null = null
const SYSTEM_CONTEXT_KEY = 'guanxian.system.context'
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

function loadSystemContext(): { associationId: string | null; enterpriseId: string | null } {
  try {
    if (typeof sessionStorage === 'undefined') return { associationId: null, enterpriseId: null }
    const parsed = JSON.parse(sessionStorage.getItem(SYSTEM_CONTEXT_KEY) || '{}') as Record<string, unknown>
    const associationId = typeof parsed.associationId === 'string' && uuid.test(parsed.associationId)
      ? parsed.associationId : null
    const enterpriseId = associationId && typeof parsed.enterpriseId === 'string' && uuid.test(parsed.enterpriseId)
      ? parsed.enterpriseId : null
    return { associationId, enterpriseId }
  } catch {
    return { associationId: null, enterpriseId: null }
  }
}

const initialSystemContext = loadSystemContext()
let systemAssociationId: string | null = initialSystemContext.associationId
let systemEnterpriseId: string | null = initialSystemContext.enterpriseId

export function getAccessToken(): string | null {
  return accessToken
}

export function setAccessToken(token: string | null): void {
  accessToken = token && token.trim() ? token.trim() : null
}

export function getDemoRole(): UserRole | null {
  return demoRole
}

export function setDemoRole(role: UserRole | null): void {
  demoRole = role
}

export function getSystemContext(): { associationId: string | null; enterpriseId: string | null } {
  return { associationId: systemAssociationId, enterpriseId: systemEnterpriseId }
}

export function setSystemContext(associationId: string | null, enterpriseId: string | null): void {
  systemAssociationId = associationId
  systemEnterpriseId = enterpriseId
  try {
    if (typeof sessionStorage === 'undefined') return
    if (associationId) sessionStorage.setItem(
      SYSTEM_CONTEXT_KEY, JSON.stringify({ associationId, enterpriseId }),
    )
    else sessionStorage.removeItem(SYSTEM_CONTEXT_KEY)
  } catch {
    // Context remains valid for the current page when storage is unavailable.
  }
}

import { request } from './http'
export interface EnterpriseAccount {
  enterpriseId: string; enterpriseName: string; enterpriseVersion: number; enabled: boolean; existingBinding: boolean
  username: string; status: 'NOT_CREATED' | 'CREATING' | 'ACTIVE' | 'RESETTING' | 'BINDING_CHANGED'; version: number
}
export interface AccountResult { account: EnterpriseAccount; temporaryPassword: string }
function etag(version: number) {
  if (!Number.isSafeInteger(version) || version < 0) throw new Error('版本无效，请刷新账号状态')
  return { 'If-Match': `"${version}"` }
}
const base = (id: string) => `/enterprise-accounts/enterprises/${encodeURIComponent(id)}`
export const enterpriseAccountsApi = {
  get: (id: string) => request<EnterpriseAccount>(base(id), { cache: 'no-store' }),
  create: (account: EnterpriseAccount, username: string, note: string, confirmed: boolean) => request<AccountResult>(base(account.enterpriseId), {
    method: 'POST', cache: 'no-store', headers: etag(account.enterpriseVersion), body: JSON.stringify({ username: username.trim(), note: note.trim(), confirmed }),
  }, undefined, 'json', 180000),
  action: (account: EnterpriseAccount, action: 'reset-password' | 'resume', note: string, confirmed: boolean) => request<AccountResult>(`${base(account.enterpriseId)}/${action}`, {
    method: 'POST', cache: 'no-store', headers: etag(account.version), body: JSON.stringify({ note: note.trim(), confirmed }),
  }, undefined, 'json', 180000),
}

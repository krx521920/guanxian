import { afterEach, describe, expect, it, vi } from 'vitest'
import type { EnterpriseAccount } from './enterprise-accounts'
const request = vi.hoisted(() => vi.fn().mockResolvedValue({}))
vi.mock('./http', () => ({ request }))
import { enterpriseAccountsApi as api } from './enterprise-accounts'
const account: EnterpriseAccount = { enterpriseId: 'enterprise-id', enterpriseName: 'fixture', enterpriseVersion: 7, version: 2, enabled: true, existingBinding: false, username: 'ent_test', status: 'NOT_CREATED' }
afterEach(() => request.mockClear())
describe('enterprise account commands', () => {
  it('creates only the selected enterprise with a version and explicit confirmation, never a browser role or password', async () => {
    await api.create(account, ' company.owner ', ' verified ', true)
    expect(request).toHaveBeenCalledWith('/enterprise-accounts/enterprises/enterprise-id', {
      method: 'POST', cache: 'no-store', headers: { 'If-Match': '"7"' }, body: JSON.stringify({ username: 'company.owner', note: 'verified', confirmed: true }),
    }, undefined, 'json', 180000)
  })
  it('reset and resume use the account operation version and never read credentials', async () => {
    await api.action(account, 'reset-password', ' verified ', true)
    expect(request).toHaveBeenLastCalledWith(expect.stringContaining('/reset-password'), expect.objectContaining({ headers: { 'If-Match': '"2"' }, body: JSON.stringify({ note: 'verified', confirmed: true }) }), undefined, 'json', 180000)
    await api.action(account, 'resume', ' verified ', true)
    expect(request).toHaveBeenLastCalledWith(expect.stringContaining('/resume'), expect.objectContaining({ headers: { 'If-Match': '"2"' } }), undefined, 'json', 180000)
    await api.get(account.enterpriseId)
    expect(request).toHaveBeenLastCalledWith(expect.any(String), { cache: 'no-store' })
  })
  it('rejects invalid versions before sending a write', () => {
    expect(() => api.action({ ...account, version: NaN }, 'resume', 'verified', true)).toThrow('版本无效')
    expect(request).not.toHaveBeenCalled()
  })
})

# OIDC idle-session recovery

## Confirmed code defects

- `automaticSilentRenew` was disabled and the access-token-expired callback cleared the verified user and transport immediately. The shell derives its navigation from that user, so token expiry could remove navigation and redirect to login even when a refresh token was still usable.
- `refreshIdentity` also cleared the user for temporary network/server failures.
- The chat identity watcher returned a new array whenever the reactive user object changed. Revalidating the same identity therefore looked like a scope switch and erased conversation/draft state.

## Fix and security boundaries

- One single-flight coordinator serves token-expiring/expired events, focus, visible-tab and online events, and authenticated HTTP/blob/SSE preflight. It uses the existing oidc-client-ts refresh-token flow, with a 60-second lead and 15-second provider request timeouts. Built-in automatic renewal stays disabled deliberately to avoid a second independent refresh-token rotation request.
- Refresh tokens remain in the existing session-scoped OIDC user store. No new password collection, permanent login, hidden-iframe fallback, token logging, or production identity-provider timeout changes are introduced. Missing/expired refresh authorization or provider `invalid_grant` still requires login.
- New tokens and identities are verified through `/users/me`; token claims never supply front-end roles. Base and delegated-scope verification use explicit request-local credentials/context, not temporary mutations of shared transport state. Temporary scoped-query failures preserve the old context but block new business requests. Confirmed revoked scope falls back to the verified base account.
- Temporary renewal/identity failures preserve the mounted workspace and unsaved state while a modal blocks interaction and the HTTP gate blocks business requests. Retry/online recovery re-verifies identity; a 10-second failure cooldown prevents request storms. Re-login explicitly warns that it leaves the current page. No draft is copied into durable browser storage.
- API 401 triggers recovery only for the current token; ordinary business 403 does not log out. Requests are **not automatically replayed**, including writes/imports and streaming chat. A caller cancelled while waiting cannot cancel other callers' renewal; requests queued across an account/scope change are rejected before sending.
- Logout invalidates late results; a refresh finishing after logout cannot restore its token/user. Actual backend role/scope changes clear old scoped views, while unchanged renewal preserves navigation, dialogs and chat drafts. Closed recovery dialogs do not intercept the floating chat's Escape key.
- Public browsing/liveness retain their separate credential-free transport. Existing server JWT validation, role/binding checks, database records, demo data and rollback references are unchanged.

Implementation references: [oidc-client-ts UserManager.signinSilent](https://authts.github.io/oidc-client-ts/classes/UserManager.html#signinSilent), [token-expiring events](https://authts.github.io/oidc-client-ts/classes/AccessTokenEvents.html#addAccessTokenExpiring). Behavior was also checked against the installed 3.5.0 source.

## Repeatable local validation

Run in `apps/web`:

```sh
npm test
npm run build
npx playwright test --config playwright.entry.config.ts
npx playwright test --config playwright.personal-model.config.ts
```

Windows can set `GUANXIAN_BROWSER_CHANNEL=msedge`; CI uses its installed Chromium.

Unit coverage includes expired stored-session renewal, concurrent wake/request coalescing, refresh rotation, transient failure/cooldown/online retry, terminal provider errors, backend role changes, 401 handling, stale scope verification, logout races, request cancellation and no write replay.

`tests/ui/session-renewal.spec.ts` runs the actual OIDC library, router, shell and HTTP transport with **synthetic local provider/business responses only**. It tests repeated token rotation, a 20-minute wall-clock sleep without timer firing, simultaneous wake events, retained sidebar/draft, 503 recovery and 320px modal layout, revoked refresh authorization, and role downgrade. This is not a live Keycloak/production end-to-end test.

## Release acceptance (not performed by local tests)

After an authorized deployment, log in through the real provider in a new session, leave a non-sensitive unsent draft, wait across the configured access-token lifetime, and verify that navigation/draft survive a successful refresh. Repeat with a background/suspended tab and temporary network loss. Check only request status/counts; do not capture token bodies. Confirm that provider-enforced session expiry/revocation still ends access and shows an explanation.

Do not treat successful synthetic tests as proof that a particular existing production session has a refresh token or that the production IdP is reachable. No production settings or data are needed for these local tests.

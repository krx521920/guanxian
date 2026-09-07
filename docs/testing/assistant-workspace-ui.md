# Conversation workspace and floating assistant

## Scope

- The association home is a conversation canvas with a large composer and four real business navigation cards.
- Existing metrics, tasks, activity and scene information remain in the expandable **业务概览** section.
- Other authenticated workspace routes use a bottom-right robot launcher. Public/anonymous pages keep their separate shell and do not gain business access.
- Personal model settings are opened through the small icon immediately left of send/stop in the composer, in both workspace and floating modes. An enabled saved provider supplies a monogram; otherwise the default cube icon is shown. Egress controls, consent and encrypted server storage behavior are unchanged.
- The workspace omits the redundant robot/name/context header. Its accessible region name is retained, and clear/cancel-and-clear remains available once a conversation starts. The floating dialog keeps its identifying title and close action.
- Shared business page headings, descriptions and action groups align with the content's left edge at every breakpoint. Only the dedicated conversation hero stays centered. Long headings/descriptions/action labels wrap instead of widening the viewport; empty action groups consume no row.
- One `ChatAssistant` instance is owned by `AppShell`. Route navigation collapses the floating panel but preserves its messages, conversation ID, draft and active response. User/role/association/enterprise changes retain the existing abort-and-clear boundaries. Browser reload persistence is not added.
- Model settings leave the conversation mounted and inert. Closing the modal restores focus to the composer icon; closing the floating chat restores focus to the robot. Nested business dialogs keep their existing Escape behavior.
- No backend changes, production writes, seed/cleanup runs, new dependencies or deployment are included.

## Baseline validation (2026-09-07)

Run from `apps/web`:

```powershell
npm run test
npm run build
$env:GUANXIAN_BROWSER_CHANNEL = 'msedge'
npx playwright test --config playwright.personal-model.config.ts
npx playwright test --config playwright.entry.config.ts
```

- 360 unit tests pass.
- Type checking and the production Vite build pass.
- 32 chat/model browser tests pass, including 6 new workspace cases. They cover desktop/mobile layout, real module links, shared conversation ID and draft, no automatic message sending, model-modal focus and unsaved key handling, identity/scope reset, and a 320px-wide active stream across navigation.
- 41 identity/enterprise browser tests pass. Three old association heading assertions were updated for the new hero title; role routing, selected navigation, inline vs floating assistant, and access boundaries are still asserted.
- Desktop and mobile workspace/floating screenshots were visually reviewed. Browser fixtures use local synthetic API/identity/model responses; these tests do **not** establish real-model answer quality or production connectivity.
- Release CI exposed an existing ambiguous invitation-test status locator: approval success and the follow-up list-loading status can coexist. The test now holds the list refresh with an explicit promise gate, verifies both distinct messages, and then verifies the bound account still has ordinary-member read-only rights. No application permission behavior was changed or test skipped.
- Model-dialog Escape tests wait for the close control to become enabled: the existing modal deliberately blocks closing while an API operation is pending. Tests still require the dialog to disappear and keyboard focus to return to its trigger. No runtime behavior is changed by this readiness synchronization.

## Alignment follow-up validation (2026-09-07)

- 360 unit tests, production typecheck/build, 36 assistant/model browser tests and 47 entry/enterprise browser tests passed locally.
- Added four geometry cases (1440/768/390/320px, workspace and floating): model icon is 8px left of send, vertically centered and the same height; model-to-send keyboard order works, opening configuration does not submit a draft, and no horizontal overflow appears. Clearing an active workspace conversation removes the header and returns focus to the composer.
- Added six real-route geometry cases (members and ecosystem overview at 1440/780/320px): heading, description, first action and content left edges match; action controls sit below copy; long unbroken values still wrap. These run through OIDC with mocked identities and fail-closed local API interception, not production credentials/data. Layout navigation performs no API writes.
- Visual artifacts include desktop/mobile and dark-theme workspace, floating dialog, member page and ecosystem overview. The long-label stress test caught a generic button nowrap override; a scoped heading-action rule fixes it without changing unrelated dialog buttons.
- Full-stack review: model settings still resolve the authenticated user, validate input, require data-egress consent and send no-store responses. Assistant requests still validate association context; members/offerings/demands retain controller authority checks and actor-scoped services. This layout fix changes no API payload, controller, business permission, database or data.

## Local interactive preview

```powershell
$env:VITE_AUTH_MODE = 'demo'
$env:VITE_API_BASE_URL = '/api/v1'
npm run dev -- --host 127.0.0.1 --port 18190 --strictPort
```

Open `/tests/ui/workspace-preview.html`. The home uses the real shell/dashboard/components and synthetic transport. Other navigation entries show a labeled module placeholder so the floating robot can be inspected without loading business data.

**Do not enter real API keys.** The fixture accepts only `preview-demo-key` for model saving. Answers are fixed local test text; refreshing clears fixture settings. Preview transport fails outside local demo development and the fixture is not part of the production entry build.

## Release and rollback boundary

The original workspace UI was merged in PR #10 and deployed as `0e15f40c43d5161f619ddde4683662e31b736128`. This alignment follow-up is developed on `codex/assistant-alignment`, from the exact same source tree (`a5d2413a7aea2bb5c41328ffff99d9f8e94e8525`). The follow-up request authorizes modification and push only, not a production deployment.

The previously agreed rollback reference `codex/rollback-point-20260907-8c8ab926` remains untouched. A later request to roll back should restore the program version while preserving newer business data unless data restoration is separately authorized.

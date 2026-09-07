# Conversation workspace and floating assistant

## Scope

- The association home is a conversation canvas with a large composer and four real business navigation cards.
- Existing metrics, tasks, activity and scene information remain in the expandable **业务概览** section.
- Other authenticated workspace routes use a bottom-right robot launcher. Public/anonymous pages keep their separate shell and do not gain business access.
- Personal model settings are opened through the small icon in the conversation header. An enabled saved provider supplies a monogram; otherwise the default cube icon is shown. Egress controls, consent and encrypted server storage behavior are unchanged.
- One `ChatAssistant` instance is owned by `AppShell`. Route navigation collapses the floating panel but preserves its messages, conversation ID, draft and active response. User/role/association/enterprise changes retain the existing abort-and-clear boundaries. Browser reload persistence is not added.
- Model settings leave the conversation mounted and inert. Closing the modal restores focus to the header icon; closing the floating chat restores focus to the robot. Nested business dialogs keep their existing Escape behavior.
- No backend changes, production writes, seed/cleanup runs, new dependencies or deployment are included.

## Validation (2026-09-07)

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
- Model-dialog Escape tests wait for the close control to become enabled: the existing modal deliberately blocks closing while an API operation is pending. Tests still require the dialog to disappear and keyboard focus to return to its header icon. No runtime behavior is changed by this readiness synchronization.

## Local interactive preview

```powershell
$env:VITE_AUTH_MODE = 'demo'
$env:VITE_API_BASE_URL = '/api/v1'
npm run dev -- --host 127.0.0.1 --port 18190 --strictPort
```

Open `/tests/ui/workspace-preview.html`. The home uses the real shell/dashboard/components and synthetic transport. Other navigation entries show a labeled module placeholder so the floating robot can be inspected without loading business data.

**Do not enter real API keys.** The fixture accepts only `preview-demo-key` for model saving. Answers are fixed local test text; refreshing clears fixture settings. Preview transport fails outside local demo development and the fixture is not part of the production entry build.

## Release and rollback boundary

This change is developed on `codex/assistant-workspace-ui`, from local commit `3d7b45a99a0556500e16d56548fa246bcd4bacbf` (same source tree as deployed `8c8ab926cebbfbd471a98e4bb6bbc74d012a43ad`). It has not been pushed, merged or deployed as part of this UI task.

The previously agreed rollback reference `codex/rollback-point-20260907-8c8ab926` remains untouched. A later request to roll back should restore the program version while preserving newer business data unless data restoration is separately authorized.

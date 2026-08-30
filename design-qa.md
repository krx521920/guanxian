# Notification popover design QA

- Source visual truth: `/var/folders/mg/ctd9153d5_g93y9n6w7tvmf80000gn/T/codex-clipboard-a26485df-f9ac-47c9-b727-33daa79d5366.png`
- User issue reference: `/var/folders/mg/ctd9153d5_g93y9n6w7tvmf80000gn/T/codex-clipboard-33be6b01-7ae8-4d1c-8104-3d9b5e249af0.png`
- Implementation screenshot: `/Users/yang/.codex/visualizations/2026/08/26/01a03c25-a22a-7ed2-9f16-90ad37b27ad3/notification-popover-redesign-2026-08-31/implementation-all.png`
- Viewport: desktop, 1292 × 994 CSS px, light theme, expanded sidebar, notification popover open on “全部”.
- Source pixels: 2518 × 1562. Implementation pixels: 1292 × 994. Comparison used the focused popover region because the source includes unrelated browser chrome and a dark demonstration canvas; no density-based pixel-perfect scaling was assumed.

**Full-view comparison evidence**

- The implementation keeps the popover anchored to the sidebar bell and opens upward from the lower-left navigation area without covering the account trigger.
- The frame is narrower and taller than the reported implementation, matching the reference’s vertical notification-feed character.
- The source is dark while the product is currently in its light theme. Product color tokens were intentionally preserved instead of copying the demo’s black canvas.

**Focused region comparison evidence**

- Tabs, active underline, divider, stacked notification rows, per-row icon, title, time, hover affordance and internal scrolling are all present.
- The settings action visible in the source is intentionally omitted because the user previously requested no settings button.
- Chinese, domain-specific notification content replaces the source placeholders while preserving the same information hierarchy.

**Findings**

- No actionable P0, P1 or P2 differences remain.
- [P3] The source uses a darker, more neutral elevation treatment; the implementation keeps the existing product’s light panel and teal tokens for system consistency.

**Interaction verification**

- “全部” loads a populated scrolling list.
- “未读” contains two seeded unread notifications.
- “已归档” contains two seeded archived notifications.
- Selecting a policy notification routes to `/policies` and closes the popover.
- Outside-click and Escape behavior remain owned by `AppShell` and were not changed.

**Comparison history**

1. Earlier implementation: 360px-wide frame, 150px empty/error state, no first-open load, and backend identity errors replaced all content.
2. Fixes: frame changed to 320px × 438px minimum, list height set to 380px, first-open loading made immediate, domain sample notifications added for all tab states, API-first fallback behavior added, and Lucide icons used for notification types.
3. Post-fix evidence: `implementation-all.png` shows four visible rows with vertical scrolling; browser state checks confirmed unread and archived content and successful navigation.

**Implementation checklist**

- [x] Narrower, taller popover frame.
- [x] Populated all, unread and archived states.
- [x] Preserve live notification API and read acknowledgement attempts.
- [x] Provide local fallback data only when the live result is unavailable or empty.
- [x] Verify tab switching and resource navigation.
- [x] Run type checks, tests and production build.

**Follow-up polish**

- Optional P3: tune shadow opacity again after the surrounding sidebar palette is finalized.

final result: passed

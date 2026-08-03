# V3 Handoff — start Phase 2 locally

## Status
- Phase 0 **ok** (constitution + UX baseline + phases)
- Phase 1 **ok** (shell / MainTab / slogan / no transport picker)
- Next: **Phase 2 — Dialogs** (local preferred)

## Docs (law)
- `docs/V3_CONSTITUTION.md`
- `docs/V3_UX_BASELINE.md` §2
- `docs/V3_PHASES.md` Phase 2 DoD

## Phase 2 scope (only)
- High density dialog list (Telegram-class)
- Search
- Pins (real, not stub)
- Archive (real, not stub)
- Unread filter
- Swipes
- Smooth scroll
- No raw id in main row

## Explicitly out of Phase 2
Chat rewrite, reactions, Expedition polish, Network rewrite, stickers.

## Branch / version suggestion
- Branch: `cursor/v3-phase-2-dialogs-ec5c` (or continue on master per repo rule)
- Bump to `0.3.2` / `tuktuk.v.0.3.2.apk`
- After DoD: release + APK link + ask «фаза 2 ок»

## Current draft to harden (not rewrite from zero)
`MainScreen.kt` → `ChatListScreen` already has denser rows + filter chips stubs.
Replace stubs with persistent pin/archive; add swipe actions.

## APK last cloud build
https://github.com/gustoa432-byte/Tuk-tuk/releases/download/v0.3.1/tuktuk.v.0.3.1.apk

## Repo
`master` is up to date with Phase 1.

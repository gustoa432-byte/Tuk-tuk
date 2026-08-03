# V3 Handoff — Phase 3 (Chat) shipped · next Phase 4

## Status
- Phase 0 **ok**
- Phase 1 **ok**
- Phase 2 **ok** (2026-08-03) · APK v0.3.2
- Phase 3 **awaiting owner «фаза 3 ок»** (2026-08-03) · APK v0.3.3
- Next after ok: **Phase 4 — Tracker**

## Docs (law)
- `docs/V3_CONSTITUTION.md`
- `docs/V3_UX_BASELINE.md` §3–4 / chat + bottom sheet
- `docs/V3_PHASES.md` Phase 3 scope + DoD

## Phase 3 shipped
- Compact auto-grow composer (~5 lines), max message area
- Reply / forward / copy / delete / edit — end-to-end (Room + ViewModel)
- Multi-select: long-press → sheet → Select; bar copy/forward/delete
- Own `ModalBottomSheet` message actions (not Alert-only)
- Schema: `reply_to_id`, `edited_at` (local) · migration 18→19

## Phase 3 DoD
- [x] Ввод компактный, auto-grow
- [x] Все действия из scope работают end-to-end (не toast-stub)
- [x] Multi-select удаляет/копирует выбранное
- [x] APK ссылка выдана

## Explicitly out of Phase 3 (still later)
Реакции, стикеры, тяжёлые image-viewer анимации.  
Expedition polish, Network rewrite, full Tracker rewrite → Phase 4+.

## Version
- Shipped: `0.3.3` / `tuktuk.v.0.3.3.apk`
- Release: https://github.com/gustoa432-byte/Tuk-tuk/releases/tag/v0.3.3

## Repo
Work on `master` per local rule.

# EnthusiaKOTH — Tasks (SPEAR)

Every task carries exactly one tag (`TDD` / `DOC` / `INFRA`), a `References:` line,
and an `Evidence:` block that MUST be filled with real source citations before any
downstream SPEAR phase runs on it.

PR grouping: tasks under each `## PR-n` header ship together in one pull request.
PR order is dependency-driven (lock semantics first — it gates GUI starts, flares,
and private tests under `MANUAL_LOCKED`).

---

## PR-0 — SPEAR bootstrap (foundation, no code review)

- [ ] **EK-000** Bootstrap SPEAR docs + Konsist architecture guard
  - Tag: `INFRA`
  - References: all REQ-001..REQ-053; `docs/implementation.md` §1, §2
  - Evidence:
  - Files: `docs/*`, `src/test/kotlin/net/badgersmc/ek/architecture/LayerRulesTest.kt`, `build.gradle.kts` (add Konsist 0.17.3)

---

## PR-1 — Locking semantics (Section C)

- [ ] **EK-101** `/ekoth lock` changes and saves only lock state — never reloads, cancels/refunds an active event, clears progress, or erases the queue
  - Tag: `TDD`
  - References: REQ-015
  - Evidence:
  - Files: `KothCommand.kt`, lock state model, tests
- [ ] **EK-102** `MANUAL_LOCKED` blocks manual commands, GUI starts, and flares; scheduled events and approved private tests still run
  - Tag: `TDD`
  - References: REQ-016
  - Evidence:
  - Files: `FlareService.kt`, GUI start path, `KothService.kt`, tests

---

## PR-2 — Private tests isolation (Section A)

- [ ] **EK-201** Lifecycle messages (lobby/start/enter/leave/capture/progress/reminder/cancel/completion) are participant-scoped during private tests
  - Tag: `TDD`
  - References: REQ-001
  - Evidence:
  - Files: `KothService.kt`, `lang/en_US.yml`
- [ ] **EK-202** Bossbar hidden from non-participants during private tests
  - Tag: `TDD`
  - References: REQ-002
  - Evidence:
  - Files: `DisplayService.kt`
- [ ] **EK-203** No Discord messages or live updates for private tests
  - Tag: `TDD`
  - References: REQ-003
  - Evidence:
  - Files: `DiscordWebhookService.kt`, `KothService.kt`
- [ ] **EK-204** No money/guild rewards/command rewards/stats/fireworks/public celebration for private tests
  - Tag: `TDD`
  - References: REQ-004
  - Evidence:
  - Files: `KothService.kt`, `FireworkCelebrationService.kt`
- [ ] **EK-205** Non-participants cannot capture, contest, damage participants, or affect the test
  - Tag: `TDD`
  - References: REQ-005
  - Evidence:
  - Files: `KothService.kt`, `RestrictionListener.kt`

---

## PR-3 — Paid starts (Section B)

- [ ] **EK-301** Restore the player-paid start flow; GUI and command share one validation/payment/start service
  - Tag: `TDD`
  - References: REQ-006, REQ-007, REQ-010
  - Evidence:
  - Files: new `StartService` (application), `KothCommand.kt`
- [ ] **EK-302** Respect `manual-start.enabled`, basic/advanced costs, and configured delay
  - Tag: `TDD`
  - References: REQ-008
  - Evidence:
  - Files: `EnthusiaKothConfig.kt`, `StartService`
- [ ] **EK-303** Charge the approved payment source (LumaGuilds guild bank — confirm Vault-vs-bank decision)
  - Tag: `TDD`
  - References: REQ-009
  - Evidence:
  - Files: `LumaGuildsAdapter.kt`, `StartService`
- [ ] **EK-304** All nonfinancial validation completes BEFORE charging
  - Tag: `TDD`
  - References: REQ-011
  - Evidence:
  - Files: `StartService`
- [ ] **EK-305** Refund on failed/thrown startup; verify the refund; alert/log if refund fails
  - Tag: `TDD`
  - References: REQ-012
  - Evidence:
  - Files: `StartService`, `KothCommand.kt`
- [ ] **EK-306** GUI starts cannot bypass payment, permissions, delay, or locks
  - Tag: `TDD`
  - References: REQ-013
  - Evidence:
  - Files: GUI path in `KothCommand.kt`, `StartService`
- [ ] **EK-307** Admin/console starts stay separate and charge nothing
  - Tag: `TDD`
  - References: REQ-014
  - Evidence:
  - Files: `KothCommand.kt`, `StartService`

---

## PR-4 — Scheduling and queueing (Section F)

- [ ] **EK-401** Occurrence starts/queues even when a tick is >30s late (remove skip window)
  - Tag: `TDD`
  - References: REQ-027
  - Evidence:
  - Files: `ScheduleService.kt`
- [ ] **EK-402** Every occurrence starts or queues exactly once; queued event survives temporary start failure
  - Tag: `TDD`
  - References: REQ-028, REQ-029
  - Evidence:
  - Files: `ScheduleService.kt`, `KothService.kt` (queue)
- [ ] **EK-403** Pre-start warnings actually send
  - Tag: `TDD`
  - References: REQ-030
  - Evidence:
  - Files: `ScheduleService.kt`, `lang/en_US.yml`
- [ ] **EK-404** Disabled schedules never appear as upcoming
  - Tag: `TDD`
  - References: REQ-031
  - Evidence:
  - Files: `ScheduleService.kt`
- [ ] **EK-405** Reload does not duplicate occurrences or erase valid queued events
  - Tag: `TDD`
  - References: REQ-032
  - Evidence:
  - Files: `ScheduleService.kt`, reload path in `ServiceModule.kt`/plugin

---

## PR-5 — PlaceholderAPI (Section E)

- [ ] **EK-501** `nextkoth` / `nextkothtime` return correct values
  - Tag: `TDD`
  - References: REQ-022
  - Evidence:
  - Files: `KothPlaceholderExpansion.kt`
- [ ] **EK-502** `wins_` parsing is case-insensitive (fix `removePrefix` bug)
  - Tag: `TDD`
  - References: REQ-023
  - Evidence:
  - Files: `KothPlaceholderExpansion.kt`
- [ ] **EK-503** Arena/time mismatch and duplicate schedule entries cannot produce wrong output
  - Tag: `TDD`
  - References: REQ-024
  - Evidence:
  - Files: `KothPlaceholderExpansion.kt`, `ScheduleService.kt`
- [ ] **EK-504** Expansion survives PlaceholderAPI reloads
  - Tag: `TDD`
  - References: REQ-025
  - Evidence:
  - Files: `KothPlaceholderExpansion.kt`, plugin enable path
- [ ] **EK-505** Direct tests for every documented placeholder
  - Tag: `TDD`
  - References: REQ-026
  - Evidence:
  - Files: `src/test/.../papi/` tests

---

## PR-6 — Statistics migration (Section D)

- [ ] **EK-601** Idempotent `stats.yml` → SQLite migration imports player and guild totals
  - Tag: `TDD`
  - References: REQ-017
  - Evidence:
  - Files: new `StatsMigration` (infrastructure/persistence), `SqlStatsRepository.kt`
- [ ] **EK-602** Per-family and overall totals preserved; wins never duplicated
  - Tag: `TDD`
  - References: REQ-018, REQ-019
  - Evidence:
  - Files: `StatsMigration`, tests
- [ ] **EK-603** YAML kept as backup; migrated counts logged; no empty leaderboard when old data exists
  - Tag: `TDD`
  - References: REQ-020, REQ-021
  - Evidence:
  - Files: `StatsMigration`, leaderboard path

---

## PR-7 — Configuration, flares, and GUI (Section G)

- [ ] **EK-701** Single timezone-fallback path (remove second unsafe `ZoneId.of` in ConfigLoader)
  - Tag: `TDD`
  - References: REQ-033
  - Evidence:
  - Files: `ConfigLoader.kt`, `EnthusiaKothConfig.kt`
- [ ] **EK-702** `flares.enabled` respected; failed/locked flare starts explain failure and preserve the flare
  - Tag: `TDD`
  - References: REQ-034, REQ-035
  - Evidence:
  - Files: `FlareService.kt`, `KothCommand.kt`
- [ ] **EK-703** GUI success announced only after the event actually starts; stable GUI holder instead of translated title
  - Tag: `TDD`
  - References: REQ-036, REQ-037
  - Evidence:
  - Files: GUI path in `KothCommand.kt`
- [ ] **EK-704** Language placeholders resolve correctly (`name` vs `id`)
  - Tag: `TDD`
  - References: REQ-038
  - Evidence:
  - Files: `lang/en_US.yml`, GUI/lore builders
- [ ] **EK-705** Every used permission declared in `paper-plugin.yml`
  - Tag: `INFRA`
  - References: REQ-039
  - Evidence:
  - Files: `paper-plugin.yml`
- [ ] **EK-706** Correct private-test `/lobby` instruction in help/lore
  - Tag: `INFRA`
  - References: REQ-040
  - Evidence:
  - Files: `lang/en_US.yml`, `KothCommand.kt`
- [ ] **EK-707** Dead config options implemented or removed (audit list)
  - Tag: `INFRA`
  - References: REQ-041
  - Evidence:
  - Files: `EnthusiaKothConfig.kt`, `config.yml`
- [ ] **EK-708** Conquest capture-speed bonuses restored OR feature/config removed consistently (decision needed)
  - Tag: `TDD`
  - References: REQ-042
  - Evidence:
  - Files: family handler in `KothService.kt`, `EnthusiaKothConfig.kt`

---

## PR-8a — Restrictions (Section H, part 1)

- [ ] **EK-801** Projectiles classified by item/projectile at launch, not shooter's hand at impact
  - Tag: `TDD`
  - References: REQ-043
  - Evidence:
  - Files: `RestrictionListener.kt`
- [ ] **EK-802** Outside-zone launches cannot bypass restrictions against inside-zone targets
  - Tag: `TDD`
  - References: REQ-044
  - Evidence:
  - Files: `RestrictionListener.kt`
- [ ] **EK-803** Slot switching never changes spear/projectile classification
  - Tag: `TDD`
  - References: REQ-045
  - Evidence:
  - Files: `RestrictionListener.kt`, `RestrictionService.kt`
- [ ] **EK-804** Gliding into an Elytra-disabled zone is prevented
  - Tag: `TDD`
  - References: REQ-046
  - Evidence:
  - Files: `RestrictionListener.kt`
- [ ] **EK-805** Cooldowns apply only after the related successful action
  - Tag: `TDD`
  - References: REQ-047
  - Evidence:
  - Files: `RestrictionService.kt`
- [ ] **EK-806** Cooldowns cleared on event complete/cancel/force-end/reload
  - Tag: `TDD`
  - References: REQ-048
  - Evidence:
  - Files: `RestrictionService.kt`, lifecycle paths in `KothService.kt`

---

## PR-8b — Runtime and ops (Section H, part 2)

- [ ] **EK-807** Completed Discord tasks removed from retained collections
  - Tag: `TDD`
  - References: REQ-049
  - Evidence:
  - Files: `DiscordWebhookService.kt`
- [ ] **EK-808** Non-2xx webhook responses treated as failure; rate limits handled safely
  - Tag: `TDD`
  - References: REQ-050
  - Evidence:
  - Files: `DiscordWebhookService.kt`
- [ ] **EK-809** Borders/fireworks rendered at the intended objective height
  - Tag: `INFRA`
  - References: REQ-051
  - Evidence:
  - Files: `ZoneBorderService.kt`, `FireworkCelebrationService.kt`
- [ ] **EK-810** Visible marker follows the moving KOTH point
  - Tag: `INFRA`
  - References: REQ-052
  - Evidence:
  - Files: `DisplayService.kt`, `KothService.kt` (moving family)
- [ ] **EK-811** Admin maintenance workflow for protected regions without weakening normal protection
  - Tag: `INFRA`
  - References: REQ-053
  - Evidence:
  - Files: `RegionProtectionService.kt`, `KothCommand.kt`

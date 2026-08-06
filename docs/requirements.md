# EnthusiaKOTH — Requirements (SPEAR)

Scope: the 2026-08 audit requirements for the Kotlin rewrite (v0.2.0, commit 9b4f2b2).
Each requirement is EARS-formatted and belongs to one of eight sections. Tasks are
derived in `docs/tasks.md` and grouped into PRs.

Legend: **Ubiquitous.** / **Event-driven.** / **State-driven.** / **Unwanted.**

---

## Section A — Private Tests

### REQ-001
**Unwanted.** IF a private test is active THEN THE SYSTEM SHALL send lobby, start, enter, leave, capture, progress, reminder, cancel, and completion messages ONLY to the owner and joined participants.

> Isolation audience: all lifecycle broadcasts are participant-scoped during private tests.

### REQ-002
**Unwanted.** IF a private test is active THEN THE SYSTEM SHALL NOT show the bossbar to players who are not participants.

### REQ-003
**Unwanted.** IF a private test is active THEN THE SYSTEM SHALL NOT send private-test messages or live updates to Discord.

### REQ-004
**Unwanted.** IF a private test is active THEN THE SYSTEM SHALL NOT award money, guild rewards, command rewards, stats, fireworks, or public celebration effects.

### REQ-005
**Unwanted.** IF a player has not joined a private test THEN THE SYSTEM SHALL NOT allow that player to capture, contest, damage participants, or otherwise affect the test.

---

## Section B — Paid Starts

### REQ-006
**Ubiquitous.** THE SYSTEM SHALL restore the intended player-paid start flow. WHERE players hold the basic or advanced start permissions.

### REQ-007
**Ubiquitous.** THE SYSTEM SHALL allow players with basic or advanced start permissions to start a paid KOTH through the GUI with behavior identical to the command.

### REQ-008
**Ubiquitous.** THE SYSTEM SHALL respect `manual-start.enabled`, the configured basic/advanced costs, and the configured delay when processing a paid start.

### REQ-009
**Ubiquitous.** THE SYSTEM SHALL charge the approved payment source for paid starts: Vault player payments unless a guild-bank replacement was explicitly approved (currently the LumaGuilds guild bank).

> Decision flag: current implementation charges the LumaGuilds guild bank (`GuildLookup.bankWithdraw`). The audit allows this "unless a guild-bank replacement was explicitly approved" — Badger approved it during the rewrite. Confirm at PR-3 kickoff; if confirmed, the requirement is satisfied by the existing economy path and only the failure/refund semantics need work.

### REQ-010
**Ubiquitous.** THE SYSTEM SHALL route command and GUI starts through the same validation, payment, and start service.

### REQ-011
**Event-driven.** WHEN a paid start is requested THEN THE SYSTEM SHALL complete all nonfinancial validation BEFORE charging the player or guild.

### REQ-012
**Event-driven.** WHEN a paid start fails or throws after payment THEN THE SYSTEM SHALL refund the payment, verify the refund succeeded, and alert or log if the refund fails.

### REQ-013
**Unwanted.** IF a GUI start is used THEN THE SYSTEM SHALL NOT bypass payment, permissions, delay, or lock checks.

### REQ-014
**Ubiquitous.** THE SYSTEM SHALL keep admin and console starts separate from paid starts WITHOUT charging a player or guild.

---

## Section C — Locking

### REQ-015
**Event-driven.** WHEN `/ekoth lock` is executed THEN THE SYSTEM SHALL change and save only the lock state AND SHALL NOT reload the plugin, cancel or refund an active event, clear progress, or erase the queue.

### REQ-016
**State-driven.** WHILE the lock is `MANUAL_LOCKED` THE SYSTEM SHALL block manual commands, GUI starts, and flares AND SHALL continue to allow scheduled events and approved private tests.

---

## Section D — Statistics Migration

### REQ-017
**Event-driven.** WHEN the plugin loads with an existing `stats.yml` and no migrated statistics THEN THE SYSTEM SHALL run an idempotent migration that imports player and guild totals.

### REQ-018
**Ubiquitous.** THE SYSTEM SHALL preserve per-family and overall totals during statistics migration.

### REQ-019
**Unwanted.** IF statistics migration runs more than once THEN THE SYSTEM SHALL NOT duplicate wins.

### REQ-020
**Ubiquitous.** THE SYSTEM SHALL keep `stats.yml` as a backup file and log the migrated record counts.

### REQ-021
**Unwanted.** IF old statistics data exists but has not yet been migrated THEN THE SYSTEM SHALL NOT show an empty leaderboard.

---

## Section E — PlaceholderAPI

### REQ-022
**Ubiquitous.** THE SYSTEM SHALL return correct values for the `nextkoth` and `nextkothtime` placeholders.

### REQ-023
**Unwanted.** IF a `wins_` placeholder parameter uses mixed case THEN THE SYSTEM SHALL parse it case-insensitively.

> Current bug: `KothPlaceholderExpansion.kt` line 72-73 checks `startsWith("wins_", ignoreCase=true)` but strips with case-sensitive `removePrefix("wins_")` — `WINS_arena` passes the guard and then fails to strip, returning garbage/empty.

### REQ-024
**Unwanted.** IF arena/time data is mismatched or schedule entries are duplicated THEN THE SYSTEM SHALL NOT emit wrong placeholder output.

### REQ-025
**Event-driven.** WHEN PlaceholderAPI reloads THEN THE SYSTEM SHALL preserve the expansion registration.

### REQ-026
**Ubiquitous.** THE SYSTEM SHALL have direct tests for every documented placeholder.

---

## Section F — Scheduling and Queueing

### REQ-027
**Event-driven.** WHEN a scheduled occurrence is due THEN THE SYSTEM SHALL start or queue it EVEN IF the tick is more than 30 seconds late.

> Current bug: `ScheduleService.tick()` (lines ~90, ~117) fires only when `Duration.between(scheduled, now).abs().toSeconds() <= 30` — a late tick past the window silently skips the occurrence.

### REQ-028
**Ubiquitous.** THE SYSTEM SHALL start or queue every scheduled occurrence exactly once.

### REQ-029
**Unwanted.** IF a queued event temporarily cannot start THEN THE SYSTEM SHALL NOT remove and lose it.

### REQ-030
**Ubiquitous.** THE SYSTEM SHALL send pre-start warnings at the configured time.

### REQ-031
**Unwanted.** IF a schedule is disabled THEN THE SYSTEM SHALL NOT show it as upcoming.

### REQ-032
**Event-driven.** WHEN the plugin reloads THEN THE SYSTEM SHALL NOT duplicate occurrences or erase valid queued events.

---

## Section G — Configuration, Flares, and GUI

### REQ-033
**Ubiquitous.** THE SYSTEM SHALL use the timezone fallback everywhere an invalid configured timezone is encountered.

> Current bug: `ConfigLoader.kt` line 124 has a second unsafe `ZoneId.of(cds(c, "general.timezone", ...))` that bypasses the runCatching fallback used at line 22-25.

### REQ-034
**Ubiquitous.** THE SYSTEM SHALL respect `flares.enabled`.

### REQ-035
**Event-driven.** WHEN a flare start fails or is locked THEN THE SYSTEM SHALL explain the failure to the player AND SHALL preserve the flare.

### REQ-036
**Event-driven.** WHEN a GUI start is requested THEN THE SYSTEM SHALL announce success only after the event actually starts.

### REQ-037
**Ubiquitous.** THE SYSTEM SHALL use a stable GUI holder/identifier instead of a translated English title.

> Current bug: `KothCommand.kt` line 108 — `Bukkit.createInventory(null, size, lang.msg("command.gui.title"))` keys the holder off the translated title.

### REQ-038
**Ubiquitous.** THE SYSTEM SHALL resolve language placeholders correctly, including `name` versus `id` references.

### REQ-039
**Ubiquitous.** THE SYSTEM SHALL declare every used permission in `paper-plugin.yml`.

### REQ-040
**Ubiquitous.** THE SYSTEM SHALL provide the correct private-test `/lobby` instruction, replacing the incorrect one.

### REQ-041
**Unwanted.** IF a config option has no effect THEN THE SYSTEM SHALL implement it or remove it.

### REQ-042
**Ubiquitous.** THE SYSTEM SHALL restore Conquest capture-speed bonuses OR consistently remove the feature and its config.

> Decision flag: keep or remove Conquest speed bonuses. Audit allows either; pick one before PR-7 execution.

---

## Section H — Restrictions and Runtime Fixes

### REQ-043
**Ubiquitous.** THE SYSTEM SHALL classify projectiles by the item/projectile at launch, NOT the shooter's current hand at impact.

### REQ-044
**Unwanted.** IF a projectile is launched outside the zone at an inside-zone target THEN THE SYSTEM SHALL NOT bypass restrictions.

### REQ-045
**Unwanted.** IF a player switches slots THEN THE SYSTEM SHALL NOT change spear/projectile classification.

### REQ-046
**Unwanted.** IF a player glides into a zone where Elytra is disabled THEN THE SYSTEM SHALL prevent the glide entry.

### REQ-047
**Event-driven.** WHEN a restricted action succeeds THEN THE SYSTEM SHALL apply the related cooldown (and not before).

### REQ-048
**Event-driven.** WHEN an event completes, is cancelled, is force-ended, or the plugin reloads THEN THE SYSTEM SHALL clear cooldowns.

### REQ-049
**Ubiquitous.** THE SYSTEM SHALL remove completed Discord tasks from retained collections.

### REQ-050
**Unwanted.** IF a webhook returns a non-2xx response THEN THE SYSTEM SHALL treat it as a failure AND SHALL handle rate limits safely.

### REQ-051
**Ubiquitous.** THE SYSTEM SHALL render borders and fireworks at the intended objective height.

### REQ-052
**Ubiquitous.** THE SYSTEM SHALL show a visible marker that follows the moving KOTH point.

### REQ-053
**Ubiquitous.** THE SYSTEM SHALL provide an admin maintenance workflow for protected regions WITHOUT weakening normal protection.

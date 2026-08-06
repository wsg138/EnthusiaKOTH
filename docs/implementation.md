# EnthusiaKOTH — Implementation Notes (SPEAR)

## 1. Layer Dependency Rules

Base package: `net.badgersmc.ek`. Enforced by `src/test/kotlin/net/badgersmc/ek/architecture/LayerRulesTest.kt` (Konsist 0.17.3):

- `domain` (`net.badgersmc.ek.domain..`) depends on nothing outside domain + Kotlin stdlib.
- `application` (`net.badgersmc.ek.application..`) depends on `domain` only.
- `infrastructure` (`net.badgersmc.ek.infrastructure..`) depends on `application` + `domain`.
- `config` and `di` packages sit outside the layer roots (config is a leaf model; `di/ServiceModule` is the composition root and may reference anything).

## 2. Forbidden Domain Annotations

The following are NOT allowed in `domain/**`: `org.bukkit.*`, `net.kyori.adventure.*`, `com.zaxxer.hikari.*`, `org.sqlite.*`, `net.badgersmc.nexus.*`. Domain types must be plain Kotlin data/classes (zero framework coupling).

## 3. Architecture Invariants (load-bearing, do not break)

- **Capture zone ≠ protection boundary** — `center ± radius` is the capture zone; `protectedRegion` is a separate larger cuboid (terrain protection only). `RegionProtectionService.isProtected()` must check both.
- **Private tests are practice** — `finishEvent()` skips stats/rewards/Discord/fireworks for `isPrivateTest`; only `event.isParticipant(uuid)` players can cap; join/leave need NO permission; only start/cancel need `enthusiakoth.privatetest`.
- **`EventKind.SCHEDULED`** exists so `MANUAL_LOCKED` blocks only manual starts (commands/flares) and never the rotation; `ScheduleService` queues with `EventKind.SCHEDULED`.
- **Timeout winner = highest score** (`resolveWinner` unified across all families) — never `currentController`.
- **Economy = LumaGuilds guild bank, not Vault** (Badger-approved replacement, REQ-009) — paid starts use `GuildLookup.bankWithdraw` (actor=guildId) + refund on failure/`forceEnd()`; family rewards deposit to guild vault; solo winners get console `eco give`.
- **MOVING path math** is the pure `KothService.movingPointAt()` (4-edge square traversal, top-left start). Keep it pure so it stays unit-testable.
- **Schedule parsing** must validate `HH:mm` bounds via `ScheduleService.parseScheduleTime()` — `LocalTime.of(25, ...)` throws and crashes the tick.
- **Reload order** (`/ekoth reload`): `kothService.shutdown()` (force-end with OLD arena objects) → `scheduleService.reset()` → `restrictionService.clear()` → `configLoader.reload()` → `langService.reload()`. Tick timers keep running; services read through volatile holders. Idempotent — two consecutive reloads must be safe.
- **Leaf/Fuji compatibility**: shade everything, no `loader:`, commands via anonymous subclass + CommandMap, `@LangFile` on Nexus LangService consumers.

## 4. Test Strategy

Unit-test pure logic by hoisting it into `companion object` functions (e.g. `KothService.movingPointAt`, `ScheduleService.parseScheduleTime`, lock-state transitions, participant-gating predicates, placeholder parsing) so tests call `Service.fn(...)` without Bukkit deps. `testImplementation("io.papermc.paper:paper-api:...")` is on the test classpath so classes that merely reference Bukkit types load.

## 5. Lang Service

All player-facing text AND color tags (`<gold>`, `<shadow:#000000:1>`, etc.) live in `lang/en_US.yml` — never in Kotlin source. `lang.msg(key)` → `Component`; `lang.legacy(key)` → `String` (bossbars, lore); `lang.raw(key)` for rare raw access. New keys start with `<prefix>` or carry `<shadow:#000000:1>` explicitly. `DiscordWebhookService` is exempt (Badger-approved hardcoded strings).

## 6. PR Execution Order

PR-0 (SPEAR bootstrap) → PR-1 (locking, gates GUI/flares/private) → PR-2 (private tests) → PR-3 (paid starts) → PR-4 (scheduling/queueing) → PR-5 (PAPI) → PR-6 (stats migration) → PR-7 (config/flares/GUI) → PR-8a (restrictions) → PR-8b (runtime/ops). Each PR is spear:spec → (prove → engine) → arch → refine per task.

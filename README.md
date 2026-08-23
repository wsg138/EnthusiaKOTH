# EnthusiaKOTH

EnthusiaKOTH is Enthusia's King of the Hill framework. It supports solo or guild-based KOTHs, manual/player starts, GUI starts, flare items, scheduled starts, protected KOTH regions, configurable combat-item restrictions, rewards, statistics, leaderboards, and staff/private-test tooling.

## Current live deployment status

The refreshed Enthusia SMP server snapshot currently has:

- global lock state: **UNLOCKED**
- scheduled KOTHs: **disabled**
- Discord webhook announcements: **disabled**
- manual basic/advanced costs: **0**
- capture arena: **disabled**
- moving arena: **disabled**
- conquest arena: **disabled**

That means the plugin is present/configured, but **none of the production KOTH arena families are currently enabled in the live snapshot**. Future wiki text should not advertise KOTH as presently runnable unless a later server snapshot shows an enabled arena.

The repository's bundled default config is newer than the live snapshot and enables some example/default arenas. Do not use bundled defaults to infer current live availability.

## Player commands

The main command is `/ekoth`.

Ordinary player-facing subcommands implemented by the plugin include:

- `/ekoth gui` — open the KOTH selection/status GUI.
- `/ekoth schedule` — show scheduled KOTH times when scheduling is enabled.
- `/ekoth top [page]` — show KOTH wins leaderboard pages.
- `/ekoth stats [player]` — show KOTH win statistics.
- `/ekoth start <arena> [basic|advanced] [solo|guild]` — request a manual KOTH start when the arena/start mode is available.

Staff-only surfaces include stopping/cancelling KOTHs, flare distribution, reload/status/lock controls, and private-test start/join/cancel flows.

## Solo and guild modes

A KOTH can run in either:

- **Solo mode** — an individual player is the capturing/winning identity.
- **Guild mode** — capture/win ownership is associated with a LumaGuilds guild.

LumaGuilds is a required runtime dependency. The plugin resolves guild membership through its guild integration rather than maintaining a separate guild system.

## Start methods

The implementation recognizes several event origins:

- player command,
- KOTH GUI,
- flare item,
- admin command,
- scheduled start,
- private test.

A global lock state controls which of these are allowed:

- `UNLOCKED` — normal start paths allowed.
- `MANUAL_LOCKED` — only scheduled, private-test and admin starts are allowed.
- `ALL_LOCKED` — no KOTH starts are allowed.

Manual starts can have separate **basic** and **advanced** Vault economy costs. The current live values are both 0, but arena availability is currently disabled.

## KOTH flares

The plugin supports special KOTH flare items. A valid flare can be used to start its configured KOTH when:

- flare use is enabled,
- the player has permission,
- start locks allow it,
- the target arena/start request is otherwise valid.

The bundled current implementation uses a custom named redstone-torch item by default, but exact live item text should be taken from the deployed config/language files.

## Arena families

### Capture KOTH

A standard capture-zone KOTH. Players/guilds fight for control of a fixed circular objective.

Important configurable behavior includes:

- capture radius,
- overall KOTH duration,
- required uninterrupted/accumulated capture time,
- what happens to capture progress after the capper leaves (`RESET`, `DECAY`, or `PAUSE`),
- capture-progress decay rate,
- whether multiple cappers contest the point,
- solo vs guild ownership,
- fixed and chance-based completion rewards.

The bundled config's example capture arena uses a 5-block capture radius, 15-minute event duration and 120-second capture requirement, but the current live arena is disabled.

### Moving KOTH

A moving-objective variant. The objective moves through the configured arena/path rather than remaining at one permanent capture point.

Relevant configuration includes the moving objective's square/path size, movement speed, duration, restrictions and rewards.

### Conquest KOTH

A conquest-style mode that uses multi-player/guild pressure and configurable capture-speed bonuses. More participants can contribute to faster capture according to configured scaling.

The implementation preserves per-player-count capture-speed bonuses and supports the same broader reward/restriction infrastructure as other arena families.

## Capture behavior and contesting

KOTH runtime tracks the current capper, progress, event state and objective position. Capture ownership can be contested according to arena rules. The plugin also exposes a progress bar and periodic reminders when configured.

The event state model supports scheduled, queued, starting, active, ending, completed and cancelled states so starts/cancellations/recovery do not have to be treated as a single transient command.

## Combat-item rules

Each KOTH family can configure whether these are allowed and, where supported, their cooldowns:

- elytra,
- mace,
- spear,
- ender pearls,
- wind charges.

Maces support a configurable mace policy rather than only a simple boolean. The current live snapshot has all listed items allowed and all configured cooldowns at 0 for each family, but the arenas themselves are disabled.

## Region protection

Each KOTH arena has a protected region separate from the capture radius. The plugin protects KOTH infrastructure/space and provides an explicit staff bypass permission for intentional maintenance.

The display system can also render KOTH objective/zone-border visuals so players can identify the active objective area.

## Rewards and economy safety

The plugin supports:

- Vault-backed player-paid starts,
- solo completion rewards,
- guild-vault completion rewards,
- arena command-style rewards,
- chance-based rewards.

Start payments are tracked with payment receipts/journaling and refund-recovery logic so a failed/cancelled start does not rely on a fragile one-shot economy transaction.

The current live snapshot has KOTH reward money values at 0 and no enabled arenas.

## Statistics and leaderboards

KOTH wins are stored in SQLite-backed statistics storage. `/ekoth top` pages through ranked wins and `/ekoth stats` exposes individual win totals. The code also contains migration support for older YAML statistics.

PlaceholderAPI integration exposes KOTH state/stat information for other server displays.

## Scheduling

The scheduler supports:

- a configured timezone,
- configured daily occurrence times,
- pre-start warnings,
- queued/event-safe lifecycle handling,
- Discord notifications when enabled.

The current live snapshot uses `America/New_York` and contains example times at 00:00, 08:00 and 16:00, but `schedule.enabled` is false, so those times are **not currently active event times**.

## Private testing

Staff can run isolated private KOTH tests with configurable quick-match and quick-capture durations. Private tests have their own permission/access model and are allowed separately from ordinary player-facing starts.

## Discord integration

Optional Discord webhook support can announce/pre-warn/live-update KOTH events. It is currently disabled in the live SMP snapshot.

## Configuration source of truth

For implementation behavior, use:

- `src/main/kotlin/` for runtime behavior,
- `src/main/resources/config.yml` for current bundled/default config structure,
- `src/main/resources/lang/en_US.yml` for player-facing language,
- `docs/config-audit.md` for which configuration keys are actually consumed.

For **current Enthusia SMP availability and values**, use the latest `enthusia-server-state` snapshot instead of repository defaults.
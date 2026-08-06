# EnthusiaKOTH — Tech Stack

| Concern | Choice |
|---|---|
| Language | Kotlin 2.1.0 (JVM), `-Xjvm-default=all` |
| JDK | 21 toolchain (temurin in CI) |
| Build | Gradle (wrapper), Shadow 8.3.6 — shade ALL runtime deps (Leaf blocks Maven Central at startup) |
| Server API | Paper 1.21.11 `paper-api:1.21.11-R0.1-SNAPSHOT` (compileOnly) |
| Framework | Nexus `v2.1.1` — nexus-core, nexus-i18n (`@LangFile` required on LangService consumers), nexus-paper-loader (NOT used — no `loader:` in paper-plugin.yml, plain `main:`) |
| Guilds | LumaGuilds v2.1.0 (compileOnly; server-provided; JitPack fallback for CI) — `ServicesManager` + `GuildLookup`, no reflection |
| PlaceholderAPI | `me.clip:placeholderapi:2.11.6` (compileOnly, provided by server) |
| DB | HikariCP 5.1.0 + sqlite-jdbc 3.45.1.0 (shaded). SQLite via Exposed-style manual JDBC in `SqlStatsRepository` |
| Logging | slf4j-nop 2.0.13 (shaded) |
| Tests | JUnit Jupiter 5.11.4, MockK 1.13.13, paper-api on test classpath, Konsist 0.17.3 |
| Commands | Anonymous `Command` subclass + `CommandMap` (`Bukkit.getCommandMap()` public in 1.21.11) |
| i18n | ALL user-facing text + colors in `lang/en_US.yml` (`<prefix>` / `<shadow:#000000:1>`); `DiscordWebhookService` exempt |
| Out of stack | Vault (unless REQ-009 decision flips), database migration frameworks, external web servers |

## CI

`.github/workflows/build.yml` — JDK 21 (temurin), Gradle cache, `./gradlew build --no-daemon`, uploads shadowJar artifact. Runs on push to `main`/`kotlin-rewrite` and PRs.

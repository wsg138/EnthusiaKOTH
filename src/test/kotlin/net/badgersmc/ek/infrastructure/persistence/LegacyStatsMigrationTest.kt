package net.badgersmc.ek.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteDataSource
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class LegacyStatsMigrationTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `empty fixture migrates idempotently without records`() {
        val file = fixture("empty.yml")
        val repository = repository(file)
        repository.init()
        val outcome = assertInstanceOf(LegacyMigrationOutcome.Success::class.java, repository.migrationOutcome)
        assertEquals(0, outcome.report.playerRecordsImported)
        assertEquals(0, outcome.report.guildRecordsImported)
        assertTrue(repository.allWins().isEmpty())
        assertTrue(File(file.parentFile, "stats.yml.pre-sqlite-backup").isFile)
        repository.shutdown()
    }

    @Test
    fun `players-only fixture preserves family total UUID and name`() {
        val file = fixture("players-only.yml")
        val repository = repository(file)
        repository.init()
        val key = "solo:11111111-1111-1111-1111-111111111111"
        assertEquals(5, repository.totalWins(key))
        assertEquals(3, repository.familyWins(key, "capture"))
        assertEquals(2, repository.familyWins(key, "moving"))
        assertEquals("Lincoln", repository.displayName(key))
        repository.shutdown()
    }

    @Test
    fun `guilds-only fixture preserves guild aggregate`() {
        val file = fixture("guilds-only.yml")
        val repository = repository(file)
        repository.init()
        val key = "guild:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        assertEquals(4, repository.totalWins(key))
        assertEquals(4, repository.familyWins(key, "conquest"))
        assertEquals("Builders", repository.displayName(key))
        repository.shutdown()
    }

    @Test
    fun `combined fixture imports players and guilds`() {
        val file = fixture("both.yml")
        val repository = repository(file)
        repository.init()
        val outcome = assertInstanceOf(LegacyMigrationOutcome.Success::class.java, repository.migrationOutcome)
        assertEquals(1, outcome.report.playerRecordsImported)
        assertEquals(1, outcome.report.guildRecordsImported)
        assertEquals(5, repository.totalWins("solo:11111111-1111-1111-1111-111111111111"))
        assertEquals(10, repository.totalWins("guild:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
        repository.shutdown()
    }

    @Test
    fun `malformed UUID is rejected without losing valid records`() {
        val file = fixture("malformed-uuid.yml")
        val repository = repository(file)
        repository.init()
        val outcome = assertInstanceOf(LegacyMigrationOutcome.Success::class.java, repository.migrationOutcome)
        assertEquals(1, outcome.report.rejected)
        assertEquals(2, repository.totalWins("solo:11111111-1111-1111-1111-111111111111"))
        assertEquals(1, repository.totalWins("guild:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
        repository.shutdown()
    }

    @Test
    fun `missing names fall back to UUID text`() {
        val file = fixture("missing-names.yml")
        val repository = repository(file)
        repository.init()
        val playerKey = "solo:22222222-2222-2222-2222-222222222222"
        val guildKey = "guild:bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        assertEquals("22222222-2222-2222-2222-222222222222", repository.displayName(playerKey))
        assertEquals("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", repository.displayName(guildKey))
        repository.shutdown()
    }

    @Test
    fun `missing family or overall totals are preserved independently`() {
        val file = fixture("missing-family-totals.yml")
        val repository = repository(file)
        repository.init()
        val overallOnly = "solo:11111111-1111-1111-1111-111111111111"
        val familiesOnly = "solo:22222222-2222-2222-2222-222222222222"
        assertEquals(9, repository.totalWins(overallOnly))
        assertEquals(0, repository.familyWins(overallOnly, "capture"))
        assertEquals(5, repository.totalWins(familiesOnly))
        assertEquals(2, repository.familyWins(familiesOnly, "capture"))
        assertEquals(3, repository.familyWins(familiesOnly, "moving"))
        repository.shutdown()
    }

    @Test
    fun `newer SQLite data is never overwritten by lower legacy values`() {
        val file = fixture("players-only.yml")
        val source = dataSource()
        val initial = SqlStatsRepository(source)
        initial.init()
        source.connection.use { connection ->
            connection.prepareStatement("INSERT INTO koth_totals(entity_key, wins) VALUES(?, ?)").use {
                it.setString(1, "solo:11111111-1111-1111-1111-111111111111")
                it.setInt(2, 20)
                it.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO koth_family_stats(entity_key, family, wins) VALUES(?, ?, ?)").use {
                it.setString(1, "solo:11111111-1111-1111-1111-111111111111")
                it.setString(2, "capture")
                it.setInt(3, 10)
                it.executeUpdate()
            }
        }
        initial.shutdown()

        val repository = SqlStatsRepository(source, legacyStatsFile = file)
        repository.init()
        assertEquals(20, repository.totalWins("solo:11111111-1111-1111-1111-111111111111"))
        assertEquals(10, repository.familyWins("solo:11111111-1111-1111-1111-111111111111", "capture"))
        val outcome = assertInstanceOf(LegacyMigrationOutcome.Success::class.java, repository.migrationOutcome)
        // Filling a previously missing display name is an imported record even when
        // newer SQLite numeric totals remain authoritative.
        assertEquals(1, outcome.report.playerRecordsImported)
        assertEquals(0, outcome.report.skipped)
        repository.shutdown()
    }

    @Test
    fun `repeated migration uses marker and does not duplicate totals`() {
        val file = fixture("both.yml")
        val source = dataSource()
        SqlStatsRepository(source, legacyStatsFile = file).also { it.init(); it.shutdown() }

        val restarted = SqlStatsRepository(source, legacyStatsFile = file)
        restarted.init()
        assertInstanceOf(LegacyMigrationOutcome.AlreadyMigrated::class.java, restarted.migrationOutcome)
        assertEquals(5, restarted.totalWins("solo:11111111-1111-1111-1111-111111111111"))
        assertEquals(10, restarted.totalWins("guild:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
        restarted.shutdown()
    }

    @Test
    fun `transaction failure rolls back and serves valid legacy fallback`() {
        val file = fixture("both.yml")
        val source = dataSource()
        val repository = SqlStatsRepository(
            dataSource = source,
            legacyStatsFile = file,
            migrationBeforeCommit = { error("injected failure") },
        )
        repository.init()

        assertInstanceOf(LegacyMigrationOutcome.Failed::class.java, repository.migrationOutcome)
        assertEquals(5, repository.totalWins("solo:11111111-1111-1111-1111-111111111111"))
        assertEquals(10, repository.totalWins("guild:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
        source.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM koth_totals").use { result ->
                    assertTrue(result.next())
                    assertEquals(0, result.getInt(1))
                }
                statement.executeQuery("SELECT COUNT(*) FROM koth_migrations").use { result ->
                    assertTrue(result.next())
                    assertEquals(0, result.getInt(1))
                }
            }
        }
        assertTrue(File(file.parentFile, "stats.yml.pre-sqlite-backup").isFile)
        repository.shutdown()
    }

    @Test
    fun `post-failure wins are additive and survive later migration`() {
        val file = fixture("players-only.yml")
        val source = dataSource()
        val key = "solo:11111111-1111-1111-1111-111111111111"
        val failed = SqlStatsRepository(
            dataSource = source,
            familyResolver = { "capture" },
            legacyStatsFile = file,
            migrationBeforeCommit = { error("injected failure") },
        )
        failed.init()
        assertEquals(5, failed.totalWins(key))

        failed.incrementWin(key, "capture-arena")
        assertEquals(6, failed.totalWins(key))
        assertEquals(4, failed.familyWins(key, "capture"))
        failed.shutdown()

        val restarted = SqlStatsRepository(
            dataSource = source,
            familyResolver = { "capture" },
            legacyStatsFile = file,
        )
        restarted.init()
        assertEquals(6, restarted.totalWins(key))
        assertEquals(4, restarted.familyWins(key, "capture"))
        restarted.shutdown()
    }

    @Test
    fun `existing arena rows backfill family aggregates`() {
        val source = dataSource()
        val key = "solo:33333333-3333-3333-3333-333333333333"
        SqlStatsRepository(source).also { it.init(); it.shutdown() }
        source.connection.use { connection ->
            connection.prepareStatement("INSERT INTO koth_stats(entity_key, koth_name, wins) VALUES(?, ?, ?)").use { statement ->
                listOf("capture-one" to 2, "capture-two" to 3).forEach { (arena, wins) ->
                    statement.setString(1, key)
                    statement.setString(2, arena)
                    statement.setInt(3, wins)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }

        val repository = SqlStatsRepository(source, familyResolver = { "capture" })
        repository.init()
        assertEquals(5, repository.totalWins(key))
        assertEquals(5, repository.familyWins(key, "capture"))
        repository.shutdown()
    }

    private fun fixture(name: String): File {
        val target = temp.resolve("stats.yml").toFile()
        javaClass.getResourceAsStream("/legacy-stats/$name").use { input ->
            requireNotNull(input) { "Missing fixture $name" }
            Files.copy(input, target.toPath())
        }
        return target
    }

    private fun repository(file: File): SqlStatsRepository = SqlStatsRepository(
        dataSource = dataSource(),
        familyResolver = { it.lowercase() },
        legacyStatsFile = file,
    )

    private fun dataSource(): SQLiteDataSource = SQLiteDataSource().apply {
        url = "jdbc:sqlite:${temp.resolve("stats.db").toAbsolutePath()}"
    }
}

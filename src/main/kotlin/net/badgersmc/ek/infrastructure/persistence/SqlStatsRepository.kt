package net.badgersmc.ek.infrastructure.persistence

import java.io.File
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

class SqlStatsRepository(
    private val dataSource: DataSource,
    private val familyResolver: (String) -> String = { it },
    private val legacyStatsFile: File? = null,
    private val logger: (String, Throwable?) -> Unit = { _, _ -> },
    private val migrationBeforeCommit: () -> Unit = {},
) {
    private data class ArenaKey(val entityKey: String, val arena: String)
    private data class FamilyKey(val entityKey: String, val family: String)

    private val arenaCache = ConcurrentHashMap<ArenaKey, Int>()
    private val familyCache = ConcurrentHashMap<FamilyKey, Int>()
    private val totalCache = ConcurrentHashMap<String, Int>()
    private val nameCache = ConcurrentHashMap<String, String>()
    private val fallbackFamilies = ConcurrentHashMap<FamilyKey, Int>()
    private val fallbackTotals = ConcurrentHashMap<String, Int>()
    private val fallbackNames = ConcurrentHashMap<String, String>()
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "EnthusiaKOTH-StatsWriter").apply { isDaemon = true }
    }

    @Volatile
    var migrationOutcome: LegacyMigrationOutcome = LegacyMigrationOutcome.NotNeeded
        private set

    fun init() {
        dataSource.connection.use { connection ->
            ensureSchema(connection)
            backfillTotals(connection)
        }
        migrationOutcome = legacyStatsFile?.let { file ->
            LegacyStatsMigrator(dataSource, logger, migrationBeforeCommit).migrate(file)
        } ?: LegacyMigrationOutcome.NotNeeded
        loadCaches()
        val failure = migrationOutcome as? LegacyMigrationOutcome.Failed
        if (failure != null) installFallback(failure.fallbackRecords)
    }

    fun incrementWin(entityKey: String, kothName: String) {
        val family = familyResolver(kothName).lowercase()
        arenaCache.merge(ArenaKey(entityKey, kothName.lowercase()), 1, Int::plus)
        familyCache.merge(FamilyKey(entityKey, family), 1, Int::plus)
        totalCache.merge(entityKey, 1, Int::plus)
    }

    fun totalWins(entityKey: String): Int = maxOf(totalCache[entityKey] ?: 0, fallbackTotals[entityKey] ?: 0)

    fun familyWins(entityKey: String, family: String): Int = maxOf(
        familyCache[FamilyKey(entityKey, family.lowercase())] ?: 0,
        fallbackFamilies[FamilyKey(entityKey, family.lowercase())] ?: 0,
    )

    fun kothWins(entityKey: String, kothName: String): Int {
        val exact = arenaCache[ArenaKey(entityKey, kothName.lowercase())] ?: 0
        return if (exact > 0) exact else familyWins(entityKey, familyResolver(kothName))
    }

    fun displayName(entityKey: String): String? = nameCache[entityKey] ?: fallbackNames[entityKey]

    fun allWins(): Map<String, Int> {
        val merged = HashMap(totalCache)
        fallbackTotals.forEach { (key, value) -> merged.merge(key, value, ::maxOf) }
        return merged
    }

    fun maxPages(pageSize: Int = 10): Int = (allWins().size + pageSize - 1) / pageSize

    fun save() {
        val arenas = HashMap(arenaCache)
        val families = HashMap(familyCache)
        val totals = HashMap(totalCache)
        val names = HashMap(nameCache)
        writer.submit {
            runCatching {
                dataSource.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        arenas.forEach { (key, wins) -> upsertArena(connection, key, wins) }
                        families.forEach { (key, wins) -> upsertFamily(connection, key, wins) }
                        totals.forEach { (entity, wins) -> upsertTotal(connection, entity, wins) }
                        names.forEach { (entity, name) -> upsertName(connection, entity, name) }
                        connection.commit()
                    } catch (error: Throwable) {
                        connection.rollback()
                        throw error
                    } finally {
                        connection.autoCommit = true
                    }
                }
            }.onFailure { logger("Failed to persist KOTH statistics snapshot", it) }
        }
    }

    fun shutdown() {
        save()
        writer.shutdown()
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                logger("KOTH statistics writer did not flush within 10 seconds; forcing shutdown", null)
                writer.shutdownNow()
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            writer.shutdownNow()
            logger("Interrupted while flushing KOTH statistics", interrupted)
        }
    }

    private fun loadCaches() {
        arenaCache.clear()
        familyCache.clear()
        totalCache.clear()
        nameCache.clear()
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT entity_key, koth_name, wins FROM koth_stats").use { result ->
                    while (result.next()) {
                        arenaCache[ArenaKey(result.getString(1), result.getString(2).lowercase())] = result.getInt(3)
                    }
                }
                statement.executeQuery("SELECT entity_key, family, wins FROM koth_family_stats").use { result ->
                    while (result.next()) {
                        familyCache[FamilyKey(result.getString(1), result.getString(2).lowercase())] = result.getInt(3)
                    }
                }
                statement.executeQuery("SELECT entity_key, wins FROM koth_totals").use { result ->
                    while (result.next()) totalCache[result.getString(1)] = result.getInt(2)
                }
                statement.executeQuery("SELECT entity_key, display_name FROM koth_entity_names").use { result ->
                    while (result.next()) nameCache[result.getString(1)] = result.getString(2)
                }
            }
        }
    }

    private fun installFallback(records: List<LegacyStatsRecord>) {
        records.forEach { record ->
            fallbackTotals.merge(record.entityKey, record.totalWins, ::maxOf)
            fallbackNames.putIfAbsent(record.entityKey, record.displayName)
            record.familyWins.forEach { (family, wins) ->
                fallbackFamilies.merge(FamilyKey(record.entityKey, family.lowercase()), wins, ::maxOf)
            }
        }
        logger("Using in-memory legacy statistics fallback because SQLite migration did not complete", null)
    }

    private fun ensureSchema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS koth_stats (
                    entity_key TEXT NOT NULL,
                    koth_name TEXT NOT NULL,
                    wins INTEGER NOT NULL,
                    PRIMARY KEY (entity_key, koth_name)
                )
                """.trimIndent(),
            )
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS koth_family_stats (entity_key TEXT NOT NULL, family TEXT NOT NULL, wins INTEGER NOT NULL, PRIMARY KEY(entity_key, family))")
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS koth_totals (entity_key TEXT PRIMARY KEY, wins INTEGER NOT NULL)")
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS koth_entity_names (entity_key TEXT PRIMARY KEY, display_name TEXT NOT NULL, updated_at INTEGER NOT NULL)")
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS koth_migrations (id TEXT PRIMARY KEY, checksum TEXT NOT NULL, completed_at INTEGER NOT NULL)")
        }
    }

    private fun backfillTotals(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT entity_key, SUM(wins) FROM koth_stats GROUP BY entity_key").use { result ->
                while (result.next()) {
                    upsertTotalMaximum(connection, result.getString(1), result.getInt(2))
                }
            }
        }
    }

    private fun upsertArena(connection: Connection, key: ArenaKey, wins: Int) {
        connection.prepareStatement(
            "INSERT INTO koth_stats(entity_key, koth_name, wins) VALUES(?, ?, ?) ON CONFLICT(entity_key, koth_name) DO UPDATE SET wins = excluded.wins",
        ).use { statement ->
            statement.setString(1, key.entityKey)
            statement.setString(2, key.arena)
            statement.setInt(3, wins)
            statement.executeUpdate()
        }
    }

    private fun upsertFamily(connection: Connection, key: FamilyKey, wins: Int) {
        connection.prepareStatement(
            "INSERT INTO koth_family_stats(entity_key, family, wins) VALUES(?, ?, ?) ON CONFLICT(entity_key, family) DO UPDATE SET wins = excluded.wins",
        ).use { statement ->
            statement.setString(1, key.entityKey)
            statement.setString(2, key.family)
            statement.setInt(3, wins)
            statement.executeUpdate()
        }
    }

    private fun upsertTotal(connection: Connection, entityKey: String, wins: Int) {
        connection.prepareStatement(
            "INSERT INTO koth_totals(entity_key, wins) VALUES(?, ?) ON CONFLICT(entity_key) DO UPDATE SET wins = excluded.wins",
        ).use { statement ->
            statement.setString(1, entityKey)
            statement.setInt(2, wins)
            statement.executeUpdate()
        }
    }

    private fun upsertTotalMaximum(connection: Connection, entityKey: String, wins: Int) {
        connection.prepareStatement(
            "INSERT INTO koth_totals(entity_key, wins) VALUES(?, ?) ON CONFLICT(entity_key) DO UPDATE SET wins = MAX(koth_totals.wins, excluded.wins)",
        ).use { statement ->
            statement.setString(1, entityKey)
            statement.setInt(2, wins)
            statement.executeUpdate()
        }
    }

    private fun upsertName(connection: Connection, entityKey: String, name: String) {
        connection.prepareStatement(
            "INSERT INTO koth_entity_names(entity_key, display_name, updated_at) VALUES(?, ?, ?) ON CONFLICT(entity_key) DO UPDATE SET display_name = excluded.display_name, updated_at = excluded.updated_at",
        ).use { statement ->
            statement.setString(1, entityKey)
            statement.setString(2, name)
            statement.setLong(3, System.currentTimeMillis())
            statement.executeUpdate()
        }
    }
}

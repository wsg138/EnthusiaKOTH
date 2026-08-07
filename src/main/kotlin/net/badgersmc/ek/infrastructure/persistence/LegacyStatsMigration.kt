package net.badgersmc.ek.infrastructure.persistence

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

data class LegacyStatsRecord(
    val entityKey: String,
    val displayName: String,
    val familyWins: Map<String, Int>,
    val totalWins: Int,
    val type: LegacyEntityType,
)

enum class LegacyEntityType { PLAYER, GUILD }

data class LegacyMigrationReport(
    val playerRecordsImported: Int,
    val guildRecordsImported: Int,
    val skipped: Int,
    val rejected: Int,
)

sealed interface LegacyMigrationOutcome {
    data object NotNeeded : LegacyMigrationOutcome
    data class AlreadyMigrated(val report: LegacyMigrationReport) : LegacyMigrationOutcome
    data class Success(val report: LegacyMigrationReport) : LegacyMigrationOutcome
    data class Failed(
        val report: LegacyMigrationReport,
        val fallbackRecords: List<LegacyStatsRecord>,
        val error: Throwable,
    ) : LegacyMigrationOutcome
}

class LegacyStatsMigrator(
    private val dataSource: DataSource,
    private val logger: (String, Throwable?) -> Unit,
    private val beforeCommit: () -> Unit = {},
) {
    companion object {
        const val MIGRATION_ID = "legacy-stats-yaml-v1"
    }

    fun migrate(file: File): LegacyMigrationOutcome {
        if (!file.isFile) return LegacyMigrationOutcome.NotNeeded
        val parsed = parse(file)
        val checksum = sha256(file)
        preserveBackup(file)

        dataSource.connection.use { connection ->
            ensureSchema(connection)
            if (hasMarker(connection, MIGRATION_ID)) {
                return LegacyMigrationOutcome.AlreadyMigrated(
                    LegacyMigrationReport(0, 0, parsed.skipped + parsed.records.size, parsed.rejected),
                )
            }

            connection.autoCommit = false
            var importedPlayers = 0
            var importedGuilds = 0
            var unchanged = 0
            try {
                parsed.records.forEach { record ->
                    val changed = importRecord(connection, record)
                    if (changed) {
                        if (record.type == LegacyEntityType.PLAYER) importedPlayers++ else importedGuilds++
                    } else unchanged++
                }
                connection.prepareStatement(
                    "INSERT INTO koth_migrations(id, checksum, completed_at) VALUES(?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, MIGRATION_ID)
                    statement.setString(2, checksum)
                    statement.setLong(3, Instant.now().toEpochMilli())
                    statement.executeUpdate()
                }
                beforeCommit()
                connection.commit()
                val report = LegacyMigrationReport(
                    playerRecordsImported = importedPlayers,
                    guildRecordsImported = importedGuilds,
                    skipped = parsed.skipped + unchanged,
                    rejected = parsed.rejected,
                )
                logReport("completed", report, null)
                return LegacyMigrationOutcome.Success(report)
            } catch (error: Throwable) {
                runCatching { connection.rollback() }
                    .onFailure { logger("Failed to roll back legacy KOTH statistics migration", it) }
                val report = LegacyMigrationReport(
                    playerRecordsImported = 0,
                    guildRecordsImported = 0,
                    skipped = parsed.skipped,
                    rejected = parsed.rejected,
                )
                logReport("failed and was rolled back", report, error)
                return LegacyMigrationOutcome.Failed(report, parsed.records, error)
            } finally {
                connection.autoCommit = true
            }
        }
    }

    internal fun parse(file: File): ParsedLegacyStats {
        val yaml = YamlConfiguration.loadConfiguration(file)
        val records = mutableListOf<LegacyStatsRecord>()
        var skipped = 0
        var rejected = 0

        fun parseRoot(root: String, type: LegacyEntityType) {
            val section = yaml.getConfigurationSection(root) ?: return
            section.getKeys(false).forEach { idText ->
                val id = runCatching { UUID.fromString(idText) }.getOrNull()
                if (id == null) {
                    rejected++
                    logger("Rejecting malformed legacy KOTH $root UUID '$idText'", null)
                    return@forEach
                }
                val recordSection = section.getConfigurationSection(idText)
                if (recordSection == null) {
                    rejected++
                    logger("Rejecting malformed legacy KOTH record '$root.$idText'", null)
                    return@forEach
                }
                val families = linkedMapOf<String, Int>()
                recordSection.getKeys(false)
                    .filterNot { it.equals("name", true) || it.equals("all", true) }
                    .forEach { key ->
                        val wins = recordSection.getInt(key, 0)
                        if (wins > 0) families[key.lowercase()] = wins
                        else if (wins < 0) rejected++
                    }
                val configuredTotal = if (recordSection.contains("all")) recordSection.getInt("all", 0) else null
                val total = configuredTotal?.takeIf { it >= 0 } ?: families.values.sum()
                if (total <= 0 && families.isEmpty()) {
                    skipped++
                    return@forEach
                }
                records += LegacyStatsRecord(
                    entityKey = "${if (type == LegacyEntityType.PLAYER) "solo" else "guild"}:$id",
                    displayName = recordSection.getString("name")?.takeIf(String::isNotBlank) ?: id.toString(),
                    familyWins = families,
                    totalWins = total.coerceAtLeast(families.values.maxOrNull() ?: 0),
                    type = type,
                )
            }
        }

        parseRoot("players", LegacyEntityType.PLAYER)
        parseRoot("guilds", LegacyEntityType.GUILD)
        return ParsedLegacyStats(records, skipped, rejected)
    }

    private fun importRecord(connection: Connection, record: LegacyStatsRecord): Boolean {
        var changed = false
        val existingTotal = selectWins(connection, "koth_totals", record.entityKey, null)
        if (record.totalWins > existingTotal) changed = true
        upsertMaximum(connection, "koth_totals", record.entityKey, null, record.totalWins)

        record.familyWins.forEach { (family, wins) ->
            val existing = selectWins(connection, "koth_family_stats", record.entityKey, family)
            if (wins > existing) changed = true
            upsertMaximum(connection, "koth_family_stats", record.entityKey, family, wins)
        }

        val existingName = connection.prepareStatement(
            "SELECT display_name FROM koth_entity_names WHERE entity_key = ?",
        ).use { statement ->
            statement.setString(1, record.entityKey)
            statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
        }
        if (existingName.isNullOrBlank()) changed = true
        connection.prepareStatement(
            """
            INSERT INTO koth_entity_names(entity_key, display_name, updated_at)
            VALUES(?, ?, ?)
            ON CONFLICT(entity_key) DO UPDATE SET
                display_name = CASE WHEN koth_entity_names.display_name = '' THEN excluded.display_name ELSE koth_entity_names.display_name END,
                updated_at = MAX(koth_entity_names.updated_at, excluded.updated_at)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, record.entityKey)
            statement.setString(2, record.displayName)
            statement.setLong(3, Instant.now().toEpochMilli())
            statement.executeUpdate()
        }
        return changed
    }

    private fun selectWins(connection: Connection, table: String, entityKey: String, dimension: String?): Int {
        val sql = if (dimension == null) {
            "SELECT wins FROM $table WHERE entity_key = ?"
        } else {
            "SELECT wins FROM $table WHERE entity_key = ? AND family = ?"
        }
        return connection.prepareStatement(sql).use { statement ->
            statement.setString(1, entityKey)
            if (dimension != null) statement.setString(2, dimension)
            statement.executeQuery().use { result -> if (result.next()) result.getInt(1) else 0 }
        }
    }

    private fun upsertMaximum(connection: Connection, table: String, entityKey: String, dimension: String?, wins: Int) {
        if (wins <= 0) return
        val sql = if (dimension == null) {
            """
            INSERT INTO $table(entity_key, wins) VALUES(?, ?)
            ON CONFLICT(entity_key) DO UPDATE SET wins = MAX($table.wins, excluded.wins)
            """.trimIndent()
        } else {
            """
            INSERT INTO $table(entity_key, family, wins) VALUES(?, ?, ?)
            ON CONFLICT(entity_key, family) DO UPDATE SET wins = MAX($table.wins, excluded.wins)
            """.trimIndent()
        }
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, entityKey)
            if (dimension == null) {
                statement.setInt(2, wins)
            } else {
                statement.setString(2, dimension)
                statement.setInt(3, wins)
            }
            statement.executeUpdate()
        }
    }

    private fun ensureSchema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS koth_migrations (id TEXT PRIMARY KEY, checksum TEXT NOT NULL, completed_at INTEGER NOT NULL)")
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS koth_family_stats (entity_key TEXT NOT NULL, family TEXT NOT NULL, wins INTEGER NOT NULL, PRIMARY KEY(entity_key, family))")
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS koth_totals (entity_key TEXT PRIMARY KEY, wins INTEGER NOT NULL)")
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS koth_entity_names (entity_key TEXT PRIMARY KEY, display_name TEXT NOT NULL, updated_at INTEGER NOT NULL)")
        }
    }

    private fun hasMarker(connection: Connection, id: String): Boolean =
        connection.prepareStatement("SELECT 1 FROM koth_migrations WHERE id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { it.next() }
        }

    private fun preserveBackup(file: File) {
        val backup = File(file.parentFile, "${file.name}.pre-sqlite-backup")
        if (backup.exists()) return
        runCatching { Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES) }
            .onFailure { logger("Failed to preserve backup of legacy KOTH statistics at ${backup.absolutePath}", it) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun logReport(status: String, report: LegacyMigrationReport, error: Throwable?) {
        logger(
            "Legacy KOTH statistics migration $status: players=${report.playerRecordsImported}, guilds=${report.guildRecordsImported}, skipped=${report.skipped}, rejected=${report.rejected}",
            error,
        )
    }
}

data class ParsedLegacyStats(
    val records: List<LegacyStatsRecord>,
    val skipped: Int,
    val rejected: Int,
)

package net.badgersmc.ek.infrastructure.persistence

import java.sql.Connection
import java.sql.ResultSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.sql.DataSource

/**
 * SQLite-backed stats storage for KOTH wins.
 * Uses HikariCP via javax.sql.DataSource.
 *
 * In-memory cache is updated synchronously (callers on the main thread get
 * immediate read-your-writes), while the actual SQLite upserts run on a
 * single-threaded executor so disk I/O never blocks the game tick.
 */
class SqlStatsRepository(
    private val dataSource: DataSource,
) {
    private val cache = ConcurrentHashMap<String, MutableMap<String, Int>>()
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "EnthusiaKOTH-Stats-Writer").apply { isDaemon = true }
    }

    fun init() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS koth_wins (
                        player_key TEXT NOT NULL,
                        koth_name  TEXT NOT NULL DEFAULT '__total__',
                        wins       INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (player_key, koth_name)
                    )
                    """.trimIndent()
                )
            }
        }
        loadCache()
    }

    private fun loadCache() {
        cache.clear()
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT player_key, koth_name, wins FROM koth_wins").use { rs ->
                    while (rs.next()) {
                        val key = rs.getString("player_key")
                        val koth = rs.getString("koth_name")
                        val wins = rs.getInt("wins")
                        cache.computeIfAbsent(key) { ConcurrentHashMap() }[koth] = wins
                    }
                }
            }
        }
    }

    fun totalWins(playerKey: String): Int =
        cache[playerKey]?.get("__total__") ?: 0

    fun kothWins(playerKey: String, kothName: String): Int =
        cache[playerKey]?.get(kothName) ?: 0

    fun incrementWin(playerKey: String, kothName: String) {
        cache.computeIfAbsent(playerKey) { ConcurrentHashMap() }.also { map ->
            map.merge("__total__", 1, Int::plus)
            map.merge(kothName, 1, Int::plus)
        }
        // Write to DB asynchronously (single-threaded, preserves ordering per key)
        ioExecutor.execute {
            upsert(playerKey, "__total__", totalWins(playerKey))
            upsert(playerKey, kothName, kothWins(playerKey, kothName))
        }
    }

    private fun upsert(playerKey: String, kothName: String, wins: Int) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO koth_wins (player_key, koth_name, wins) VALUES (?, ?, ?)
                ON CONFLICT(player_key, koth_name) DO UPDATE SET wins = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, playerKey)
                stmt.setString(2, kothName)
                stmt.setInt(3, wins)
                stmt.setInt(4, wins)
                stmt.executeUpdate()
            }
        }
    }

    fun topWins(limit: Int = 10): List<Pair<String, Int>> =
        cache.entries
            .mapNotNull { (key, map) -> map["__total__"]?.let { key to it } }
            .sortedByDescending { it.second }
            .take(limit)

    fun allWins(): Map<String, Int> =
        cache.entries.associate { (key, map) -> key to (map["__total__"] ?: 0) }

    fun maxPages(): Int = ((cache.size + 9) / 10).coerceAtLeast(1)

    fun save() {
        // Already written on each increment — cache is just a read-through
    }

    /** Flushes pending writes and shuts down the writer thread (call on plugin disable). */
    fun shutdown() {
        ioExecutor.shutdown()
    }
}

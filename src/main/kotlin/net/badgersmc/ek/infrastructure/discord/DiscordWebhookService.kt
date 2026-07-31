package net.badgersmc.ek.infrastructure.discord

import net.badgersmc.ek.domain.TeamId
import net.badgersmc.ek.domain.TeamMode
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import net.badgersmc.ek.stripColors
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant

/**
 * Discord webhook service for KOTH live updates and capture announcements.
 * Uses built-in java.net.http.HttpClient (JDK 11+) — no external deps.
 */
class DiscordWebhookService(
    private val plugin: JavaPlugin,
    private val webhookUrl: () -> String,
    private val enabled: () -> Boolean,
    private val guilds: LumaGuildsAdapter,
) {
    private val client = HttpClient.newHttpClient()
    private val json = JsonWriter()
    private val pendingTasks = mutableListOf<org.bukkit.scheduler.BukkitTask>()

    /** Sends a live-update embed with the current capper and contest status */
    fun sendLiveUpdate(kothName: String, capper: TeamId?, isContested: Boolean, timeLeft: String) {
        if (!enabled() || webhookUrl().isBlank()) return
        sendEmbed(buildLiveEmbed(kothName, capper, isContested, timeLeft))
    }

    /** Sends a capture-announcement embed */
    fun sendCapture(kothName: String, winner: TeamId, wasContested: Boolean) {
        if (!enabled() || webhookUrl().isBlank()) return
        sendEmbed(buildCaptureEmbed(kothName, winner, wasContested))
    }

    /** Sends a KOTH start embed */
    fun sendStart(kothName: String, location: String) {
        if (!enabled() || webhookUrl().isBlank()) return
        sendEmbed(buildStartEmbed(kothName, location))
    }

    // --- Embed builders ---

    private fun buildLiveEmbed(kothName: String, capper: TeamId?, contested: Boolean, timeLeft: String): String {
        val color = if (contested) 0xE74C3C else 0xF1C40F
        val status = if (contested) "⚔️ Contested! Multiple groups fighting!" else "🟢 Stable capture"
        val capperName = if (capper != null) resolveName(capper) else "Nobody"
        return json.embed(color, "🏆 KOTH — $kothName") {
            field("KOTH", kothName, inline = true)
            field("Currently Capped By", capperName, inline = true)
            field("Time Left", timeLeft, inline = true)
            field("Status", status, inline = false)
            timestamp()
        }
    }

    private fun buildCaptureEmbed(kothName: String, winner: TeamId, contested: Boolean): String {
        val color = if (contested) 0x9B59B6 else 0x2ECC71 // purple if contested won, green if clean
        val name = resolveName(winner)
        val verb = if (contested) "fought off the competition and captured" else "captured"
        return json.embed(color, "🎉 KOTH Captured!") {
            field("KOTH", kothName, inline = true)
            field("Captured By", name, inline = true)
            field("Result", "$name $verb $kothName!", inline = false)
            timestamp()
        }
    }

    private fun buildStartEmbed(kothName: String, location: String): String {
        return json.embed(0x3498DB, "🔥 KOTH Started!") {
            field("KOTH", kothName, inline = true)
            field("Location", location, inline = true)
            timestamp()
        }
    }

    // --- Helpers ---

    private fun resolveName(team: TeamId): String {
        val raw = if (team.mode == TeamMode.GUILD) {
            guilds.guildName(team.id) ?: team.id.toString().take(8)
        } else {
            plugin.server.getOfflinePlayer(team.id).name ?: team.id.toString().take(8)
        }
        return raw.stripColors()
    }

    /** Cancel all pending async webhook tasks. Call from onDisable(). */
    fun shutdown() {
        pendingTasks.forEach { it.cancel() }
        pendingTasks.clear()
    }

    private fun sendEmbed(jsonPayload: String) {
        if (!enabled() || webhookUrl().isBlank()) return
        val task = object : BukkitRunnable() {
            override fun run() {
                try {
                    val request = HttpRequest.newBuilder()
                        .uri(URI.create(webhookUrl()))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                        .timeout(java.time.Duration.ofSeconds(5))
                        .build()
                    client.send(request, HttpResponse.BodyHandlers.ofString())
                } catch (e: Exception) {
                    plugin.logger.warning("Discord webhook failed: ${e.message}")
                }
            }
        }.runTaskAsynchronously(plugin)
        pendingTasks.add(task)
    }
}

/**
 * Minimal JSON writer for Discord embed payloads.
 * Avoids Gson/Jackson dependency entirely.
 */
private class JsonWriter {
    fun embed(color: Int, title: String, block: EmbedBuilder.() -> Unit): String {
        val eb = EmbedBuilder()
        eb.block()
        return """{"embeds":[${eb.build(color, title)}]}"""
    }
}

private class EmbedBuilder {
    private val fields = mutableListOf<String>()
    private var thumbnailUrl: String? = null
    private var imageUrl: String? = null
    private var includeTimestamp = false

    fun field(name: String, value: String, inline: Boolean = false) {
        fields.add("""{"name":${jsonStr(name)},"value":${jsonStr(value)},"inline":$inline}""")
    }

    fun image(url: String, _unused: String? = null) { imageUrl = url }
    fun thumbnail(url: String) { thumbnailUrl = url }
    fun timestamp() { includeTimestamp = true }

    fun build(color: Int, title: String): String {
        val parts = mutableListOf(
            """"title":${jsonStr(title)}""",
            """"color":$color""",
        )
        if (fields.isNotEmpty()) {
            parts.add(""""fields":[${fields.joinToString(",")}]""")
        }
        if (includeTimestamp) {
            parts.add(""""timestamp":"${Instant.now()}"""")
        }
        if (thumbnailUrl != null) {
            parts.add(""""thumbnail":{"url":${jsonStr(thumbnailUrl!!)}}""")
        }
        if (imageUrl != null) {
            parts.add(""""image":{"url":${jsonStr(imageUrl!!)}}""")
        }
        return "{${parts.joinToString(",")}}"
    }

    private fun jsonStr(s: String): String {
        val escaped = s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}

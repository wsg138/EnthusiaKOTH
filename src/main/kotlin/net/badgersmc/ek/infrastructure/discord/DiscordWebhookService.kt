package net.badgersmc.ek.infrastructure.discord

import net.badgersmc.ek.domain.TeamId
import net.badgersmc.ek.domain.TeamMode
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import net.badgersmc.ek.stripColors
import org.bukkit.plugin.java.JavaPlugin
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

internal enum class WebhookDeliveryKind { LIVE_UPDATE, IMPORTANT }

internal data class WebhookDelivery(
    val payload: String,
    val kind: WebhookDeliveryKind,
    val attempts: Int = 0,
)

internal data class WebhookResponse(
    val statusCode: Int,
    val retryAfter: Duration? = null,
)

internal fun interface WebhookTransport {
    fun send(url: String, payload: String): WebhookResponse
}

internal class DiscordDeliveryBuffer(private val capacity: Int) {
    private val deliveries = ArrayDeque<WebhookDelivery>()

    fun offer(delivery: WebhookDelivery): Boolean {
        if (delivery.kind == WebhookDeliveryKind.LIVE_UPDATE) {
            deliveries.removeIf { it.kind == WebhookDeliveryKind.LIVE_UPDATE }
        }
        if (deliveries.size >= capacity) {
            val removableLive = deliveries.firstOrNull { it.kind == WebhookDeliveryKind.LIVE_UPDATE }
            if (removableLive != null) {
                deliveries.remove(removableLive)
            } else {
                return false
            }
        }
        deliveries.addLast(delivery)
        return true
    }

    fun retryFirst(delivery: WebhookDelivery): Boolean {
        if (deliveries.size >= capacity) {
            val removableLive = deliveries.lastOrNull { it.kind == WebhookDeliveryKind.LIVE_UPDATE }
                ?: return false
            deliveries.remove(removableLive)
        }
        deliveries.addFirst(delivery)
        return true
    }

    fun poll(): WebhookDelivery? = deliveries.pollFirst()
    fun clear() = deliveries.clear()
    fun isNotEmpty(): Boolean = deliveries.isNotEmpty()
    fun size(): Int = deliveries.size
    fun snapshotKinds(): List<WebhookDeliveryKind> = deliveries.map { it.kind }
}

internal object DiscordRetryPolicy {
    const val MAX_ATTEMPTS = 4

    fun delayFor(response: WebhookResponse, attempts: Int): Duration? {
        if (attempts >= MAX_ATTEMPTS) return null
        return when {
            response.statusCode == 429 -> response.retryAfter?.coerceIn(Duration.ofMillis(250), Duration.ofMinutes(2))
                ?: Duration.ofSeconds(1)
            response.statusCode in 500..599 -> exponentialDelay(attempts)
            else -> null
        }
    }

    fun delayForFailure(attempts: Int): Duration? =
        if (attempts >= MAX_ATTEMPTS) null else exponentialDelay(attempts)

    private fun exponentialDelay(attempts: Int): Duration =
        Duration.ofSeconds((1L shl attempts.coerceIn(0, 5)).coerceAtMost(30))
}

internal class HttpWebhookTransport(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build(),
) : WebhookTransport {
    override fun send(url: String, payload: String): WebhookResponse {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .timeout(Duration.ofSeconds(5))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return WebhookResponse(response.statusCode(), retryAfter(response))
    }

    private fun retryAfter(response: HttpResponse<*>): Duration? {
        val seconds = response.headers().firstValue("Retry-After").orElse(null)?.toDoubleOrNull()
            ?: response.headers().firstValue("X-RateLimit-Reset-After").orElse(null)?.toDoubleOrNull()
        if (seconds != null) return Duration.ofMillis((seconds * 1_000.0).toLong().coerceAtLeast(0))
        val date = response.headers().firstValue("Retry-After").orElse(null) ?: return null
        return runCatching {
            Duration.between(Instant.now(), ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())
                .coerceAtLeast(Duration.ZERO)
        }.getOrNull()
    }
}

/**
 * Bounded, single-worker Discord webhook dispatcher.
 *
 * Bukkit tasks are intentionally not used: completed tasks cannot accumulate in plugin state and shutdown can
 * reject all further work immediately. Live updates are coalesced while start/capture/pre-start messages are kept.
 */
class DiscordWebhookService internal constructor(
    private val plugin: JavaPlugin,
    private val webhookUrl: () -> String,
    private val enabled: () -> Boolean,
    private val guilds: LumaGuildsAdapter,
    private val transport: WebhookTransport = HttpWebhookTransport(),
    queueCapacity: Int = 32,
) {
    private val json = JsonWriter()
    private val lock = Any()
    private val buffer = DiscordDeliveryBuffer(queueCapacity.coerceAtLeast(1))
    private val executor = ScheduledThreadPoolExecutor(1, ThreadFactory { task ->
        Thread(task, "EnthusiaKOTH-Discord").apply { isDaemon = true }
    }).apply {
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
        continueExistingPeriodicTasksAfterShutdownPolicy = false
    }
    private var scheduled: ScheduledFuture<*>? = null
    private var inFlight = false
    @Volatile private var closed = false

    fun sendLiveUpdate(kothName: String, capper: TeamId?, isContested: Boolean, timeLeft: String) {
        if (!canSend()) return
        enqueue(WebhookDelivery(buildLiveEmbed(kothName, capper, isContested, timeLeft), WebhookDeliveryKind.LIVE_UPDATE))
    }

    fun sendCapture(kothName: String, winner: TeamId, wasContested: Boolean) {
        if (!canSend()) return
        enqueue(WebhookDelivery(buildCaptureEmbed(kothName, winner, wasContested), WebhookDeliveryKind.IMPORTANT))
    }

    fun sendStart(kothName: String, location: String) {
        if (!canSend()) return
        enqueue(WebhookDelivery(buildStartEmbed(kothName, location), WebhookDeliveryKind.IMPORTANT))
    }

    fun sendPreStart(kothName: String, minutes: Int) {
        if (!canSend() || minutes <= 0) return
        enqueue(WebhookDelivery(buildPreStartEmbed(kothName, minutes), WebhookDeliveryKind.IMPORTANT))
    }

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
        val color = if (contested) 0x9B59B6 else 0x2ECC71
        val name = resolveName(winner)
        val verb = if (contested) "fought off the competition and captured" else "captured"
        return json.embed(color, "🎉 KOTH Captured!") {
            field("KOTH", kothName, inline = true)
            field("Captured By", name, inline = true)
            field("Result", "$name $verb $kothName!", inline = false)
            timestamp()
        }
    }

    private fun buildStartEmbed(kothName: String, location: String): String =
        json.embed(0x3498DB, "🔥 KOTH Started!") {
            field("KOTH", kothName, inline = true)
            field("Location", location, inline = true)
            timestamp()
        }

    private fun buildPreStartEmbed(kothName: String, minutes: Int): String =
        json.embed(0xF39C12, "⏰ KOTH Starting Soon") {
            field("KOTH", kothName, inline = true)
            field("Starts In", "$minutes minute${if (minutes == 1) "" else "s"}", inline = true)
            timestamp()
        }

    private fun resolveName(team: TeamId): String {
        val raw = if (team.mode == TeamMode.GUILD) {
            guilds.guildName(team.id) ?: team.id.toString().take(8)
        } else {
            plugin.server.getOfflinePlayer(team.id).name ?: team.id.toString().take(8)
        }
        return raw.stripColors()
    }

    fun shutdown() {
        synchronized(lock) {
            closed = true
            buffer.clear()
            scheduled?.cancel(true)
            scheduled = null
            inFlight = false
        }
        executor.shutdownNow()
    }

    internal fun pendingCount(): Int = synchronized(lock) { buffer.size() }

    private fun canSend(): Boolean = !closed && enabled() && webhookUrl().isNotBlank()

    private fun enqueue(delivery: WebhookDelivery) {
        val accepted = synchronized(lock) {
            if (closed) return
            val offered = buffer.offer(delivery)
            if (offered && !inFlight) scheduleLocked(Duration.ZERO)
            offered
        }
        if (!accepted) plugin.logger.warning("Discord webhook queue is full; dropping ${delivery.kind.name.lowercase()}")
    }

    private fun scheduleLocked(delay: Duration) {
        if (closed || inFlight || !buffer.isNotEmpty() || scheduled?.isDone == false) return
        try {
            scheduled = executor.schedule(::deliverNext, delay.toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            closed = true
            buffer.clear()
        }
    }

    private fun deliverNext() {
        val delivery = synchronized(lock) {
            scheduled = null
            if (closed || inFlight) return
            val next = buffer.poll() ?: return
            inFlight = true
            next
        }

        val url = webhookUrl()
        if (!enabled() || url.isBlank()) {
            synchronized(lock) {
                inFlight = false
                scheduleLocked(Duration.ZERO)
            }
            return
        }

        val retryDelay = try {
            val response = transport.send(url, delivery.payload)
            when {
                response.statusCode in 200..299 -> null
                response.statusCode == 429 || response.statusCode in 500..599 -> {
                    DiscordRetryPolicy.delayFor(response, delivery.attempts).also { delay ->
                        if (delay == null) {
                            plugin.logger.warning("Discord webhook failed with HTTP ${response.statusCode} after ${delivery.attempts + 1} attempts")
                        }
                    }
                }
                else -> {
                    plugin.logger.warning("Discord webhook rejected with HTTP ${response.statusCode}; delivery dropped")
                    null
                }
            }
        } catch (_: IllegalArgumentException) {
            // URI.create() can include the full offending URL in its exception text.
            // Discord webhook URLs contain a secret token, so never log that message.
            plugin.logger.warning("Discord webhook URL is invalid; delivery dropped")
            null
        } catch (error: Exception) {
            DiscordRetryPolicy.delayForFailure(delivery.attempts).also { delay ->
                if (delay == null) plugin.logger.warning("Discord webhook failed after ${delivery.attempts + 1} attempts: ${error.message}")
            }
        }

        val retryQueued = synchronized(lock) {
            inFlight = false
            if (closed) return
            val queued = retryDelay == null || buffer.retryFirst(delivery.copy(attempts = delivery.attempts + 1))
            scheduleLocked(if (queued) retryDelay ?: Duration.ZERO else Duration.ZERO)
            queued
        }
        if (!retryQueued) {
            plugin.logger.warning("Discord webhook retry queue is full; dropping ${delivery.kind.name.lowercase()}")
        }
    }

    private fun scheduleRemaining(delay: Duration) = synchronized(lock) {
        if (!closed) scheduleLocked(delay)
    }
}

private class JsonWriter {
    fun embed(color: Int, title: String, block: EmbedBuilder.() -> Unit): String {
        val builder = EmbedBuilder()
        builder.block()
        return """{"embeds":[${builder.build(color, title)}]}"""
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
        if (fields.isNotEmpty()) parts.add(""""fields":[${fields.joinToString(",")}]""")
        if (includeTimestamp) parts.add(""""timestamp":"${Instant.now()}"""")
        thumbnailUrl?.let { parts.add(""""thumbnail":{"url":${jsonStr(it)}}""") }
        imageUrl?.let { parts.add(""""image":{"url":${jsonStr(it)}}""") }
        return "{${parts.joinToString(",")}}"
    }

    private fun jsonStr(value: String): String {
        val escaped = value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}

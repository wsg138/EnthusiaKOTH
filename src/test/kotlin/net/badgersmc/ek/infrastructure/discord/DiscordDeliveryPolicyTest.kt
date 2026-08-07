package net.badgersmc.ek.infrastructure.discord

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsAdapter
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

class DiscordDeliveryPolicyTest {
    @Test
    fun `rate limit delay cannot be bypassed by enqueue while request is in flight`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val transport = WebhookTransport { _, _ ->
            calls.incrementAndGet()
            started.countDown()
            release.await(2, TimeUnit.SECONDS)
            WebhookResponse(429, Duration.ofSeconds(1))
        }
        val plugin = mockk<JavaPlugin>(relaxed = true)
        every { plugin.logger } returns Logger.getLogger("DiscordDeliveryPolicyTest")
        val service = DiscordWebhookService(
            plugin = plugin,
            webhookUrl = { "https://discord.com/api/webhooks/123/token" },
            enabled = { true },
            guilds = mockk<LumaGuildsAdapter>(relaxed = true),
            transport = transport,
        )
        try {
            service.sendStart("first", "0, 0, 0")
            assertTrue(started.await(2, TimeUnit.SECONDS))
            service.sendStart("second", "0, 0, 0")
            release.countDown()

            Thread.sleep(150)
            assertEquals(1, calls.get(), "a concurrent enqueue bypassed Discord's Retry-After delay")
        } finally {
            release.countDown()
            service.shutdown()
        }
    }

    @Test
    fun `live updates are coalesced instead of accumulating`() {
        val buffer = DiscordDeliveryBuffer(4)
        assertTrue(buffer.offer(WebhookDelivery("first", WebhookDeliveryKind.LIVE_UPDATE)))
        assertTrue(buffer.offer(WebhookDelivery("latest", WebhookDeliveryKind.LIVE_UPDATE)))

        assertEquals(1, buffer.size())
        assertEquals("latest", buffer.poll()?.payload)
    }

    @Test
    fun `important delivery displaces live update under pressure`() {
        val buffer = DiscordDeliveryBuffer(2)
        buffer.offer(WebhookDelivery("live", WebhookDeliveryKind.LIVE_UPDATE))
        buffer.offer(WebhookDelivery("start", WebhookDeliveryKind.IMPORTANT))

        assertTrue(buffer.offer(WebhookDelivery("capture", WebhookDeliveryKind.IMPORTANT)))
        assertEquals(
            listOf(WebhookDeliveryKind.IMPORTANT, WebhookDeliveryKind.IMPORTANT),
            buffer.snapshotKinds(),
        )
    }

    @Test
    fun `live update is dropped when bounded queue contains only important deliveries`() {
        val buffer = DiscordDeliveryBuffer(2)
        buffer.offer(WebhookDelivery("start", WebhookDeliveryKind.IMPORTANT))
        buffer.offer(WebhookDelivery("capture", WebhookDeliveryKind.IMPORTANT))

        assertFalse(buffer.offer(WebhookDelivery("live", WebhookDeliveryKind.LIVE_UPDATE)))
        assertEquals(2, buffer.size())
    }

    @Test
    fun `retry does not silently evict an important delivery when queue is full`() {
        val buffer = DiscordDeliveryBuffer(2)
        buffer.offer(WebhookDelivery("start", WebhookDeliveryKind.IMPORTANT))
        buffer.offer(WebhookDelivery("capture", WebhookDeliveryKind.IMPORTANT))

        assertFalse(buffer.retryFirst(WebhookDelivery("retry", WebhookDeliveryKind.IMPORTANT)))
        assertEquals(2, buffer.size())
        assertEquals(listOf(WebhookDeliveryKind.IMPORTANT, WebhookDeliveryKind.IMPORTANT), buffer.snapshotKinds())
    }

    @Test
    fun `retry displaces a pending live update before an important delivery`() {
        val buffer = DiscordDeliveryBuffer(2)
        buffer.offer(WebhookDelivery("start", WebhookDeliveryKind.IMPORTANT))
        buffer.offer(WebhookDelivery("live", WebhookDeliveryKind.LIVE_UPDATE))

        assertTrue(buffer.retryFirst(WebhookDelivery("retry", WebhookDeliveryKind.IMPORTANT)))
        assertEquals(listOf(WebhookDeliveryKind.IMPORTANT, WebhookDeliveryKind.IMPORTANT), buffer.snapshotKinds())
    }

    @Test
    fun `retry policy honors rate limit and bounds retries`() {
        assertEquals(
            Duration.ofSeconds(7),
            DiscordRetryPolicy.delayFor(WebhookResponse(429, Duration.ofSeconds(7)), attempts = 0),
        )
        assertEquals(Duration.ofSeconds(2), DiscordRetryPolicy.delayFor(WebhookResponse(503), attempts = 1))
        assertNull(
            DiscordRetryPolicy.delayFor(
                WebhookResponse(429, Duration.ofSeconds(7)),
                attempts = DiscordRetryPolicy.MAX_ATTEMPTS,
            ),
        )
        assertNull(DiscordRetryPolicy.delayFor(WebhookResponse(400), attempts = 0))
    }
}

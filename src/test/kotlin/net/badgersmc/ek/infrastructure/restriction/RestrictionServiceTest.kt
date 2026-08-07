package net.badgersmc.ek.infrastructure.restriction

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.ek.domain.CaptureZone
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.domain.KothEvent
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class RestrictionServiceTest {
    private val playerId = UUID.randomUUID()
    private val player = mockk<Player> { every { uniqueId } returns playerId }

    @Test
    fun `cooldowns are scoped to one event and cleared on termination`() {
        val clock = MutableClock(Instant.parse("2026-08-06T12:00:00Z"))
        val service = RestrictionService(rulesForArena = { RuleSet(maceCooldownSeconds = 30) }, clock = clock)
        val first = event()
        val second = event()
        val mace = item(Material.MACE)

        assertTrue(service.canDealDamageWith(player, first, mace).allowed)
        service.applyCooldown(player, first, RestrictedItemType.MACE)
        assertFalse(service.canDealDamageWith(player, first, mace).allowed)
        assertTrue(service.canDealDamageWith(player, second, mace).allowed)

        service.clearEvent(first.id)
        assertTrue(service.canDealDamageWith(player, first, mace).allowed)
    }

    @Test
    fun `projectile snapshot preserves launch item and cannot cross event ids`() {
        val service = RestrictionService(rulesForArena = { RuleSet.PERMISSIVE })
        val first = event()
        val second = event()
        val projectileId = UUID.randomUUID()
        val launchItem = item(Material.MACE)

        service.recordProjectile(first, projectileId, player, launchItem)
        val snapshot = service.projectileSnapshot(first, projectileId)
        assertEquals(Material.MACE, snapshot?.item?.type)
        assertNotSame(launchItem, snapshot?.item)
        assertNull(service.projectileSnapshot(second, projectileId))
        assertEquals(1, service.trackedProjectileCount(first.id))

        service.clearEvent(first.id)
        assertNull(service.projectileSnapshot(first, projectileId))
    }

    private fun item(material: Material): ItemStack {
        val item = mockk<ItemStack>()
        val snapshot = mockk<ItemStack>()
        every { item.type } returns material
        every { item.clone() } returns snapshot
        every { snapshot.type } returns material
        every { snapshot.clone() } returns snapshot
        return item
    }

    private fun event() = KothEvent(
        id = UUID.randomUUID(),
        arena = KothArena(
            id = "capture",
            family = "capture",
            zone = mockk<CaptureZone>(relaxed = true),
            durationSeconds = 60,
            captureSeconds = 10,
        ),
        startsAt = Instant.EPOCH,
        endsAt = Instant.EPOCH.plusSeconds(60),
    )

    private class MutableClock(private var instant: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = instant
    }
}

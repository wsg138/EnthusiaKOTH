package net.badgersmc.ek.infrastructure.restriction

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.ek.application.KothService
import net.badgersmc.ek.domain.CaptureZone
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.domain.KothEvent
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.AbstractWindCharge
import org.bukkit.entity.EnderCrystal
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class RestrictionListenerTest {
    @Test
    fun `private participant is isolated from nonparticipant placed crystal damage`() {
        val fixture = fixture(RuleSet(), privateTest = true)
        val victim = mockk<Player>(relaxed = true)
        val victimId = UUID.randomUUID()
        every { victim.uniqueId } returns victimId
        every { victim.location } returns fixture.inside
        fixture.event.join(victimId)

        val crystal = mockk<EnderCrystal>(relaxed = true)
        val crystalId = UUID.randomUUID()
        every { crystal.uniqueId } returns crystalId
        every { crystal.location } returns fixture.inside
        fixture.restrictions.recordIndirectSource(fixture.event, crystalId, UUID.randomUUID())

        val damage = mockk<EntityDamageByEntityEvent>(relaxed = true)
        every { damage.damager } returns crystal
        every { damage.entity } returns victim
        every { damage.isCancelled } returns false

        fixture.listener.onDamage(damage)

        verify { damage.isCancelled = true }
    }

    @Test
    fun `cancelled projectile launch never creates snapshot or cooldown`() {
        val fixture = fixture(RuleSet(enderPearlCooldownSeconds = 30))
        val projectile = mockk<Projectile>(relaxed = true)
        every { projectile.shooter } returns fixture.player
        every { projectile.uniqueId } returns UUID.randomUUID()
        val launch = mockk<ProjectileLaunchEvent>(relaxed = true)
        every { launch.isCancelled } returns true
        every { launch.entity } returns projectile

        fixture.listener.onProjectileAccepted(launch)

        assertEquals(0, fixture.restrictions.trackedProjectileCount(fixture.event.id))
    }

    @Test
    fun `damage uses launch snapshot instead of current hand`() {
        val fixture = fixture(RuleSet(maceRule = MaceRule.FULLY_DISABLED))
        val projectile = mockk<Projectile>(relaxed = true)
        every { projectile.shooter } returns fixture.player
        every { projectile.uniqueId } returns UUID.randomUUID()
        every { projectile.location } returns fixture.inside
        fixture.restrictions.recordProjectile(
            fixture.event,
            projectile.uniqueId,
            fixture.player,
            item(Material.MACE),
        )
        every { fixture.inventory.itemInMainHand } returns item(Material.AIR)
        val victim = mockk<Entity>(relaxed = true)
        every { victim.location } returns fixture.inside
        val damage = mockk<EntityDamageByEntityEvent>(relaxed = true)
        every { damage.damager } returns projectile
        every { damage.entity } returns victim
        every { damage.isCancelled } returns false

        fixture.listener.onDamage(damage)

        verify { damage.isCancelled = true }
    }

    @Test
    fun `arrow snapshot uses its launch weapon rather than a restricted current hand`() {
        val fixture = fixture(RuleSet(maceRule = MaceRule.FULLY_DISABLED))
        val arrow = mockk<AbstractArrow>(relaxed = true)
        every { arrow.shooter } returns fixture.player
        every { arrow.uniqueId } returns UUID.randomUUID()
        every { arrow.location } returns fixture.inside
        every { arrow.weapon } returns item(Material.BOW)
        every { fixture.inventory.itemInMainHand } returns item(Material.MACE)
        val launch = mockk<ProjectileLaunchEvent>(relaxed = true)
        every { launch.entity } returns arrow
        every { launch.isCancelled } returns false

        fixture.listener.onProjectileAccepted(launch)

        assertEquals(
            Material.BOW,
            fixture.restrictions.projectileSnapshot(fixture.event, arrow.uniqueId)?.item?.type,
        )
        val victim = mockk<Entity>(relaxed = true)
        every { victim.location } returns fixture.inside
        val damage = mockk<EntityDamageByEntityEvent>(relaxed = true)
        every { damage.damager } returns arrow
        every { damage.entity } returns victim
        every { damage.isCancelled } returns false

        fixture.listener.onDamage(damage)

        verify(exactly = 0) { damage.isCancelled = true }
    }

    @Test
    fun `wind charge damage is not denied by the cooldown started for that charge`() {
        val fixture = fixture(RuleSet(windChargeCooldownSeconds = 30))
        val charge = mockk<AbstractWindCharge>(relaxed = true)
        every { charge.shooter } returns fixture.player
        every { charge.uniqueId } returns UUID.randomUUID()
        every { charge.location } returns fixture.inside
        fixture.restrictions.recordProjectile(
            fixture.event,
            charge.uniqueId,
            fixture.player,
            item(Material.WIND_CHARGE),
            launchedInsideZone = false,
        )
        fixture.restrictions.applyCooldown(
            fixture.player,
            fixture.event,
            RestrictedItemType.WIND_CHARGE,
        )
        val victim = mockk<Entity>(relaxed = true)
        every { victim.location } returns fixture.inside
        val damage = mockk<EntityDamageByEntityEvent>(relaxed = true)
        every { damage.damager } returns charge
        every { damage.entity } returns victim
        every { damage.isCancelled } returns false

        fixture.listener.onDamage(damage)

        verify(exactly = 0) { damage.isCancelled = true }
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

    private fun fixture(rules: RuleSet, privateTest: Boolean = false): Fixture {
        val world = mockk<World>()
        every { world.name } returns "world"
        val inside = Location(world, 5.0, 64.0, 5.0)
        val zone = CaptureZone(
            "capture",
            "world",
            Location(world, 0.0, 0.0, 0.0),
            Location(world, 10.0, 100.0, 10.0),
        )
        val event = KothEvent(
            UUID.randomUUID(),
            KothArena("capture", "capture", zone, durationSeconds = 60, captureSeconds = 10),
            Instant.EPOCH,
            Instant.EPOCH.plusSeconds(60),
            isPrivateTest = privateTest,
        )
        val inventory = mockk<PlayerInventory>(relaxed = true)
        every { inventory.itemInMainHand } returns item(Material.AIR)
        every { inventory.itemInOffHand } returns item(Material.AIR)
        val player = mockk<Player>(relaxed = true)
        every { player.uniqueId } returns UUID.randomUUID()
        every { player.location } returns inside
        every { player.inventory } returns inventory
        val koth = mockk<KothService>()
        every { koth.activeEvent } returns event
        val restrictions = RestrictionService(rulesForArena = { rules })
        return Fixture(event, player, inventory, inside, restrictions, RestrictionListener(koth, restrictions))
    }

    private data class Fixture(
        val event: KothEvent,
        val player: Player,
        val inventory: PlayerInventory,
        val inside: Location,
        val restrictions: RestrictionService,
        val listener: RestrictionListener,
    )
}

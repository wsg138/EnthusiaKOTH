package net.badgersmc.ek.infrastructure.bukkit

import net.badgersmc.ek.application.FireworkCelebrationService
import net.badgersmc.ek.application.FlareService
import net.badgersmc.ek.application.KothService
import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.domain.KothArena
import org.bukkit.Material
import org.bukkit.entity.Firework
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerInteractEvent

class KothListeners(
    private val cfgLoader: () -> EnthusiaKothConfig,
    private val kothService: KothService,
    private val flareService: FlareService,
    private val arenas: () -> Map<String, KothArena>,
    private val command: KothCommand,
    private val lang: net.badgersmc.nexus.i18n.LangService,
) : Listener {
    @EventHandler
    fun onFlareUse(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.RIGHT_CLICK_AIR) return
        val item = event.item ?: return
        if (item.type == Material.AIR) return
        if (flareService.handleFlareUse(event.player, item, event.hand == org.bukkit.inventory.EquipmentSlot.OFF_HAND)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onGuiClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? KothGuiHolder ?: return
        event.isCancelled = true
        if (event.clickedInventory == event.view.topInventory && event.whoClicked is org.bukkit.entity.Player && event.slot >= 0) {
            command.handleGuiClick(event.whoClicked as org.bukkit.entity.Player, event.slot, holder)
        }
    }

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val active = kothService.activeEvent ?: return
        if (active.arena.zone.contains(event.block.location) && !event.player.hasPermission("enthusiakoth.admin")) {
            event.isCancelled = true
            event.player.sendMessage(lang.msg("protection.no_break"))
        }
    }

    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        val active = kothService.activeEvent ?: return
        if (active.arena.zone.contains(event.block.location) && !event.player.hasPermission("enthusiakoth.admin")) {
            event.isCancelled = true
            event.player.sendMessage(lang.msg("protection.no_place"))
        }
    }

    @EventHandler
    fun onFireworkDamage(event: EntityDamageByEntityEvent) {
        if (event.damager is Firework && (event.damager as Firework).scoreboardTags.contains(FireworkCelebrationService.EKOTH_FIREWORK_TAG)) {
            event.isCancelled = true
        }
    }
}

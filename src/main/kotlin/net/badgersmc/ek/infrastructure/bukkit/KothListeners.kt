package net.badgersmc.ek.infrastructure.bukkit

import net.badgersmc.ek.application.FlareService
import net.badgersmc.ek.application.FireworkCelebrationService
import net.badgersmc.ek.application.KothService
import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.toComponent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.entity.EntityType
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

        val handled = flareService.handleFlareUse(event.player, item, event.hand == org.bukkit.inventory.EquipmentSlot.OFF_HAND)
        if (handled) event.isCancelled = true
    }

    @EventHandler
    fun onGuiClick(event: InventoryClickEvent) {
        val title = PlainTextComponentSerializer.plainText().serialize(event.view.title())
        if (title.contains("KOTHs")) {
            event.isCancelled = true
            // Only handle clicks in the GUI itself — clicks in the player's own
            // inventory (bottom) have slots relative to that inventory and must
            // never be treated as GUI slot indexes.
            if (event.clickedInventory == event.view.topInventory
                && event.whoClicked is org.bukkit.entity.Player
                && event.slot >= 0
            ) {
                command.handleGuiClick(event.whoClicked as org.bukkit.entity.Player, event.slot)
            }
        }
    }

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val ev = kothService.activeEvent ?: return
        if (ev.arena.zone.contains(event.block.location) && !event.player.hasPermission("enthusiakoth.admin")) {
            event.isCancelled = true
            event.player.sendMessage(lang.msg("protection.no_break"))
        }
    }

    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        val ev = kothService.activeEvent ?: return
        if (ev.arena.zone.contains(event.block.location) && !event.player.hasPermission("enthusiakoth.admin")) {
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

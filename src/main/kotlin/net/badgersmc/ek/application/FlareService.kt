package net.badgersmc.ek.application

import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.toComponent
import net.badgersmc.ek.toLore
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType

class FlareService(
    private val cfgLoader: () -> EnthusiaKothConfig,
    private val kothService: KothService,
    private val arenas: () -> Map<String, KothArena>,
    private val lang: net.badgersmc.nexus.i18n.LangService,
) {
    companion object {
        val FLARE_KOTH_KEY = NamespacedKey("ekoth", "koth-flare")
    }

    fun createFlare(arena: KothArena): ItemStack {
        val cfg = cfgLoader()
        val flare = cfg.flares
        val mat = Material.getMaterial(flare.item.material) ?: Material.REDSTONE_TORCH
        val item = ItemStack(mat)
        val meta = item.itemMeta ?: return item

        val nameStr = flare.item.name.replace("{KOTH}", arena.id)
        meta.displayName(nameStr.toComponent())
        meta.lore(flare.item.lore.map { it.replace("{KOTH}", arena.id) }.toLore())

        // Store the KOTH ID in PersistentDataContainer — no fragile string parsing
        meta.persistentDataContainer.set(FLARE_KOTH_KEY, PersistentDataType.STRING, arena.id)

        item.itemMeta = meta
        return item
    }

    fun getFlareKothName(item: ItemStack): String? {
        val meta = item.itemMeta ?: return null
        return meta.persistentDataContainer.get(FLARE_KOTH_KEY, PersistentDataType.STRING)
    }

    fun handleFlareUse(player: Player, item: ItemStack, offHand: Boolean): Boolean {
        val kothName = getFlareKothName(item) ?: return false
        val arena = arenas()[kothName] ?: return false

        if (arena.flaresMustBePlacedOnCap && !arena.zone.contains(player.location)) {
            player.sendMessage(lang.msg("flare.not_in_region", "koth" to kothName))
            return true
        }

        if (kothService.activeEvent != null) {
            player.sendMessage(lang.msg("flare.already_active", "koth" to kothName))
            return true
        }

        // Only consume the flare if the event actually starts
        val started = kothService.startEvent(arena)
        if (!started) return true

        // Consume from the hand the flare was used from — clearing the wrong
        // slot (e.g. main hand when the last offhand flare was used) would
        // delete an unrelated item.
        item.amount -= 1
        if (item.amount <= 0) {
            if (offHand) {
                player.inventory.setItemInOffHand(null)
            } else {
                player.inventory.setItemInMainHand(null)
            }
        }

        player.sendMessage(lang.msg("flare.started", "koth" to kothName))
        val loc = arena.zone.center(player.world)
        Bukkit.broadcast(
            lang.msg("flare.started_broadcast",
                "player" to player.name,
                "koth" to kothName,
                "location" to "${loc.blockX}, ${loc.blockY}, ${loc.blockZ}")
        )
        return true
    }
}

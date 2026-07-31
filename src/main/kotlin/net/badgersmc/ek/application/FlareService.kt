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

    fun handleFlareUse(player: Player, item: ItemStack): Boolean {
        val kothName = getFlareKothName(item) ?: return false
        val arena = arenas()[kothName] ?: return false
        val cfg = cfgLoader()

        if (arena.flaresMustBePlacedOnCap && !arena.zone.contains(player.location)) {
            player.sendMessage(cfg.flares.messages.notInRegion
                .replace("{KOTH}", kothName).toComponent())
            return true
        }

        if (kothService.activeEvent != null) {
            player.sendMessage(cfg.flares.messages.alreadyActive
                .replace("{KOTH}", kothName).toComponent())
            return true
        }

        item.amount -= 1
        if (item.amount <= 0) {
            player.inventory.setItemInMainHand(null)
        }

        kothService.startEvent(arena)

        player.sendMessage(cfg.flares.messages.startedWithFlare
            .replace("{KOTH}", kothName).toComponent())
        val loc = arena.zone.center(player.world)
        Bukkit.broadcast(
            cfg.flares.messages.startedBroadcast
                .replace("{KOTH}", kothName)
                .replace("{PLAYER}", player.name)
                .replace("{LOCATION}", "${loc.blockX}, ${loc.blockY}, ${loc.blockZ}")
                .toComponent()
        )
        return true
    }
}

package net.badgersmc.ek.application

import net.badgersmc.ek.config.EnthusiaKothConfig
import net.badgersmc.ek.domain.KothArena
import net.badgersmc.ek.toComponent
import net.badgersmc.ek.toLore
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class FlareService(
    private val cfgLoader: () -> EnthusiaKothConfig,
    private val startService: StartService,
    private val arenas: () -> Map<String, KothArena>,
    private val lang: net.badgersmc.nexus.i18n.LangService,
) {
    companion object {
        val FLARE_KOTH_KEY = NamespacedKey("ekoth", "koth-flare")
    }

    fun createFlare(arena: KothArena): ItemStack {
        val flare = cfgLoader().flares
        val item = ItemStack(Material.getMaterial(flare.item.material) ?: Material.REDSTONE_TORCH)
        val meta = item.itemMeta ?: return item
        meta.displayName(flare.item.name.replace("{KOTH}", arena.id).toComponent())
        meta.lore(flare.item.lore.map { it.replace("{KOTH}", arena.id) }.toLore())
        meta.persistentDataContainer.set(FLARE_KOTH_KEY, PersistentDataType.STRING, arena.id)
        item.itemMeta = meta
        return item
    }

    fun getFlareKothName(item: ItemStack): String? =
        item.itemMeta?.persistentDataContainer?.get(FLARE_KOTH_KEY, PersistentDataType.STRING)

    fun handleFlareUse(player: Player, item: ItemStack, offHand: Boolean): Boolean {
        val arenaId = getFlareKothName(item) ?: return false
        val arena = arenas()[arenaId] ?: run {
            player.sendMessage(lang.msg("command.error.koth_not_found_short"))
            return true
        }
        if (arena.flaresMustBePlacedOnCap && !arena.zone.contains(player.location)) {
            player.sendMessage(lang.msg("flare.not_in_region", "koth" to arenaId))
            return true
        }
        val result = startService.start(
            StartRequest(
                actor = StartActor(
                    playerId = player.uniqueId,
                    isAdmin = player.hasPermission("enthusiakoth.admin"),
                    canStartBasic = player.hasPermission("enthusiakoth.start.basic"),
                    canStartAdvanced = player.hasPermission("enthusiakoth.start.advanced"),
                    canUseFlare = player.hasPermission("enthusiakoth.flare.use"),
                ),
                arena = arena,
                source = StartSource.FLARE,
            ),
        )
        if (result is StartResult.Rejected) {
            player.sendMessage(lang.msg(FlareUsePolicy.rejectionKey(result), "koth" to arenaId))
            return true
        }

        val hand = if (offHand) FlareHand.OFF else FlareHand.MAIN
        val consumption = FlareUsePolicy.consumption(item.amount, hand)
        item.amount = consumption.newAmount
        when (consumption.clearHand) {
            FlareHand.MAIN -> player.inventory.setItemInMainHand(null)
            FlareHand.OFF -> player.inventory.setItemInOffHand(null)
            null -> Unit
        }

        player.sendMessage(lang.msg("flare.started", "koth" to arenaId))
        val location = arena.zone.center(player.world)
        Bukkit.broadcast(
            lang.msg(
                "flare.started_broadcast",
                "player" to player.name,
                "koth" to arenaId,
                "location" to "${location.blockX}, ${location.blockY}, ${location.blockZ}",
            ),
        )
        return true
    }
}

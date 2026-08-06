package net.badgersmc.ek.application

import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

class DisplayService(
    private val plugin: JavaPlugin,
    private val lang: net.badgersmc.nexus.i18n.LangService,
) : Listener {
    private var bossBar: BossBar? = null
    private var viewers: Set<UUID> = emptySet()
    private var publicAudience = false

    fun showKoth(
        kothName: String,
        capper: String?,
        timeLeft: String,
        contested: Boolean,
        audience: Collection<Player>,
        isPublic: Boolean,
    ) {
        val text = if (capper != null) {
            lang.msg(
                "bossbar.format_with_capper",
                "koth_name" to kothName,
                "capper" to capper,
                "contested" to if (contested) lang.msg("bossbar.contested") else net.kyori.adventure.text.Component.empty(),
                "time" to timeLeft,
            )
        } else {
            lang.msg("bossbar.format_no_capper", "koth_name" to kothName, "time" to timeLeft)
        }
        val bar = bossBar ?: BossBar.bossBar(text, 1.0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS).also {
            bossBar = it
        }
        bar.name(text)
        val desired = audience.mapTo(mutableSetOf()) { it.uniqueId }
        Bukkit.getOnlinePlayers().forEach { player ->
            if (player.uniqueId in desired) bar.addViewer(player) else bar.removeViewer(player)
        }
        viewers = desired
        publicAudience = isPublic
    }

    @EventHandler(ignoreCancelled = true)
    fun onJoin(event: PlayerJoinEvent) {
        if (publicAudience || event.player.uniqueId in viewers) bossBar?.addViewer(event.player)
    }

    fun clear() {
        bossBar?.let { bar -> Bukkit.getOnlinePlayers().forEach(bar::removeViewer) }
        bossBar = null
        viewers = emptySet()
        publicAudience = false
    }

    fun updateActive(eventId: String?) {
        if (eventId == null) clear()
    }
}

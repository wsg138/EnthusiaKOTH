package net.badgersmc.ek.application

import net.badgersmc.ek.toComponent
import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

/**
 * Display service: bossbar for active KOTH.
 */
class DisplayService(private val plugin: JavaPlugin) {

    private var bossBar: BossBar? = null

    /** Show or update a bossbar for the active KOTH */
    fun showKoth(kothName: String, capper: String?, timeLeft: String, contested: Boolean) {
        val text = buildString {
            append("§6§l$kothName")
            if (capper != null) {
                append(" §8| §a$capper")
                if (contested) append(" §c⚔")
            }
            append(" §8| §7$timeLeft")
        }
        val bar = bossBar ?: BossBar.bossBar(
            text.toComponent(),
            1.0f,
            BossBar.Color.RED,
            BossBar.Overlay.PROGRESS,
        ).also { b ->
            Bukkit.getOnlinePlayers().forEach { b.addViewer(it) }
            bossBar = b
        }
        bar.name(text.toComponent())
    }

    fun clear() {
        bossBar?.let { bar ->
            Bukkit.getOnlinePlayers().forEach { bar.removeViewer(it) }
        }
        bossBar = null
    }

    fun updateActive(eventId: String?) {
        if (eventId == null) clear()
    }
}

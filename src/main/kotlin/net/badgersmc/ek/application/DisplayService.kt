package net.badgersmc.ek.application

import net.badgersmc.ek.toComponent
import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

/**
 * Display service: bossbar for active KOTH.
 */
class DisplayService(
    private val plugin: JavaPlugin,
    private val lang: net.badgersmc.nexus.i18n.LangService,
) {

    private var bossBar: BossBar? = null

    /** Show or update a bossbar for the active KOTH */
    fun showKoth(kothName: String, capper: String?, timeLeft: String, contested: Boolean) {
        val text = if (capper != null) {
            lang.msg("bossbar.format_with_capper",
                "koth_name" to kothName,
                "capper" to capper,
                "contested" to (if (contested) lang.msg("bossbar.contested") else net.kyori.adventure.text.Component.empty()),
                "time" to timeLeft,
            )
        } else {
            lang.msg("bossbar.format_no_capper",
                "koth_name" to kothName,
                "time" to timeLeft,
            )
        }
        val bar = bossBar ?: BossBar.bossBar(
            text,
            1.0f,
            BossBar.Color.RED,
            BossBar.Overlay.PROGRESS,
        ).also { b ->
            Bukkit.getOnlinePlayers().forEach { b.addViewer(it) }
            bossBar = b
        }
        bar.name(text)
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

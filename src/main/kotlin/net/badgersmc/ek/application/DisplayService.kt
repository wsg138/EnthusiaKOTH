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
        val capperText = if (capper != null) lang.msg("bossbar.format", 
            "koth" to ("<gold><bold>$kothName" as Any),
            "separator" to (lang.msg("bossbar.separator").toString()),
            "capper" to ("<green>$capper" as Any),
            "contested" to (if (contested) lang.msg("bossbar.contested").toString() else ""),
            "time" to ("<gray>$timeLeft" as Any),
        ) else lang.msg("bossbar.format", 
            "koth" to ("<gold><bold>$kothName" as Any),
            "separator" to (lang.msg("bossbar.separator").toString()),
            "capper" to "",
            "contested" to "",
            "time" to ("<gray>$timeLeft" as Any),
        )
        val bar = bossBar ?: BossBar.bossBar(
            capperText,
            1.0f,
            BossBar.Color.RED,
            BossBar.Overlay.PROGRESS,
        ).also { b ->
            Bukkit.getOnlinePlayers().forEach { b.addViewer(it) }
            bossBar = b
        }
        bar.name(capperText)
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

package net.badgersmc.ek.infrastructure.bukkit

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ConfigSurfaceTest {
    private val config = YamlConfiguration.loadConfiguration(File("src/main/resources/config.yml"))

    @Test
    fun `dead duplicate message settings are not exposed`() {
        listOf(
            "messages",
            "flares.messages",
            "progress-bar.format",
            "reminders.format",
            "storage.stats-file",
            "private-testing.show-objective-particles",
        ).forEach { assertFalse(config.contains(it), "Dead or misleading key remains: $it") }
    }

    @Test
    fun `required runtime settings remain documented`() {
        listOf(
            "manual-start.enabled",
            "manual-start.basic-cost",
            "manual-start.advanced-cost",
            "manual-start.delay-seconds",
            "flares.enabled",
            "schedule.enabled",
            "schedule.pre-start-warning-seconds",
            "general.timezone",
            "discord.pre-start-ping-minutes",
            "rewards.capture.solo-vault-money",
            "arenas.conquest.capture-speed-bonuses",
        ).forEach { assertTrue(config.contains(it), "Required key missing: $it") }
        assertEquals(6, config.getInt("config-version"))
    }

    @Test
    fun `conquest speed bonuses remain positive`() {
        val bonuses = config.getConfigurationSection("arenas.conquest.capture-speed-bonuses")!!
        assertTrue(bonuses.getKeys(false).isNotEmpty())
        bonuses.getKeys(false).forEach { count ->
            assertTrue(count.toInt() > 0)
            assertTrue(bonuses.getDouble(count) > 0.0)
        }
    }
}

package net.badgersmc.ek

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginCompatibilityTest {
    @Test
    fun `LumaGuilds compatibility floor matches getGuildMemberIds release`() {
        assertFalse(EnthusiaKothPlugin.isVersionAtLeast("2.1.0", EnthusiaKothPlugin.MIN_LUMAGUILDS_VERSION))
        assertFalse(EnthusiaKothPlugin.isVersionAtLeast("2.1.7", EnthusiaKothPlugin.MIN_LUMAGUILDS_VERSION))
        assertTrue(EnthusiaKothPlugin.isVersionAtLeast("2.1.8", EnthusiaKothPlugin.MIN_LUMAGUILDS_VERSION))
        assertTrue(EnthusiaKothPlugin.isVersionAtLeast("2.1.8-SNAPSHOT", EnthusiaKothPlugin.MIN_LUMAGUILDS_VERSION))
        assertTrue(EnthusiaKothPlugin.isVersionAtLeast("2.1.12", EnthusiaKothPlugin.MIN_LUMAGUILDS_VERSION))
        assertTrue(EnthusiaKothPlugin.isVersionAtLeast("2.2.0", EnthusiaKothPlugin.MIN_LUMAGUILDS_VERSION))
    }

    @Test
    fun `PlaceholderAPI is declared required when its expansion is linked directly`() {
        val descriptor = checkNotNull(javaClass.classLoader.getResource("paper-plugin.yml"))
            .readText()
        val placeholderBlock = descriptor
            .substringAfter("PlaceholderAPI:")
            .substringBefore("Vault:")

        assertTrue(placeholderBlock.contains("required: true"))
    }
}

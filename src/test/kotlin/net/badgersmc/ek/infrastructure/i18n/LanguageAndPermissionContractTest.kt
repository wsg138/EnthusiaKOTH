package net.badgersmc.ek.infrastructure.i18n

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class LanguageAndPermissionContractTest {
    @Test
    fun `every literal language key referenced by Kotlin exists`() {
        val language = YamlConfiguration.loadConfiguration(File("src/main/resources/lang/en_US.yml"))
        val keyPattern = Regex("(?:lang|langService)\\.(?:msg|raw)\\(\\s*\"([^\"]+)\"")
        val references = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> keyPattern.findAll(file.readText()).map { it.groupValues[1] } }
            .filterNot { '$' in it }
            .toSortedSet()
        val missing = references.filterNot(language::contains)
        assertTrue(missing.isEmpty(), "Missing language keys: $missing")
    }

    @Test
    fun `every literal source permission is declared`() {
        val descriptor = YamlConfiguration.loadConfiguration(File("src/main/resources/paper-plugin.yml"))
        val permissionPattern = Regex("hasPermission\\(\\s*\"(enthusiakoth\\.[^\"]+)\"")
        val references = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> permissionPattern.findAll(file.readText()).map { it.groupValues[1] } }
            .toSortedSet()
        val missing = references.filterNot { descriptor.contains("permissions.$it") }
        assertTrue(missing.isEmpty(), "Missing permission declarations: $missing")
    }

    @Test
    fun `required maintenance bypass defaults to operators`() {
        val descriptor = YamlConfiguration.loadConfiguration(File("src/main/resources/paper-plugin.yml"))
        assertTrue(descriptor.contains("permissions.enthusiakoth.protection.bypass"))
        assertTrue(descriptor.getString("permissions.enthusiakoth.protection.bypass.default").equals("op", true))
    }
}

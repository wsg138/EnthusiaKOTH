package net.badgersmc.ek

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import java.util.stream.Collectors

/**
 * Converts legacy §/& color-coded strings to Adventure Components.
 */
fun String.toComponent(): Component =
    LegacyComponentSerializer.legacySection().deserialize(this.replace('&', '§'))

/**
 * Converts a list of legacy §/& color-coded strings to a list of Components.
 */
fun List<String>.toLore(): List<Component> =
    this.map { it.toComponent() }

/**
 * Strips all Minecraft color codes (§ + digit/letter) and MiniMessage tags (<tag>)
 * from a string. Used before sending names to Discord, plain-text places like PAPI, etc.
 *
 * Handles:
 * - Legacy § codes: §a, §c, §l, §k, §r, etc.
 * - MiniMessage tags: <red>, <bold>, <gradient:red:blue>, </red>, <#FF0000>
 */
fun String.stripColors(): String =
    this.replace(Regex("[§&][0-9a-fk-orxA-FK-ORX]"), "")       // §a, &a, §l, §r, §x (RGB prefix), etc.
        .replace(Regex("<[^>]+>"), "")                         // <red>, </red>, <#FF0000>, <gradient:...>

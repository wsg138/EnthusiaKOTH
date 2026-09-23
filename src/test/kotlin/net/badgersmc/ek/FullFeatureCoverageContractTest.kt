package net.badgersmc.ek

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Inventory guard for major KOTH production feature families.
 *
 * This does not replace behavioral assertions. A feature is added to this map only after a real
 * regression test exists for it.
 */
class FullFeatureCoverageContractTest {
    @Test
    fun `major production feature families retain regression evidence`() {
        val root = repositoryRoot()
        coverage().forEach { (feature, paths) ->
            paths.forEach { relative ->
                assertTrue(
                    Files.isRegularFile(root.resolve(relative)),
                    "$feature lost required regression evidence: $relative",
                )
            }
        }
    }

    private fun coverage(): Map<String, List<String>> = linkedMapOf(
        "capture takeover and event modes" to listOf(
            "src/test/kotlin/net/badgersmc/ek/application/CaptureTakeoverTest.kt",
            "src/test/kotlin/net/badgersmc/ek/application/KothEventModeTest.kt",
        ),
        "queue lifecycle" to listOf(
            "src/test/kotlin/net/badgersmc/ek/application/KothQueueTest.kt",
        ),
        "paid-start lifecycle and recovery" to listOf(
            "src/test/kotlin/net/badgersmc/ek/application/KothPaymentLifecycleTest.kt",
            "src/test/kotlin/net/badgersmc/ek/application/PendingRefundRecoveryTest.kt",
            "src/test/kotlin/net/badgersmc/ek/application/PaymentRecoveryPolicyTest.kt",
        ),
        "start authorization and team modes" to listOf(
            "src/test/kotlin/net/badgersmc/ek/application/StartServiceTest.kt",
            "src/test/kotlin/net/badgersmc/ek/application/StartTeamModeTest.kt",
        ),
        "flare policy" to listOf(
            "src/test/kotlin/net/badgersmc/ek/application/FlareUsePolicyTest.kt",
        ),
        "schedule and timezone behavior" to listOf(
            "src/test/kotlin/net/badgersmc/ek/application/ScheduleServiceTest.kt",
            "src/test/kotlin/net/badgersmc/ek/application/ScheduleTimeTest.kt",
            "src/test/kotlin/net/badgersmc/ek/infrastructure/bukkit/TimezoneParserTest.kt",
        ),
        "moving objective and markers" to listOf(
            "src/test/kotlin/net/badgersmc/ek/application/MovingPathTest.kt",
            "src/test/kotlin/net/badgersmc/ek/application/ObjectiveMarkerServiceTest.kt",
        ),
        "restrictions and region protection" to listOf(
            "src/test/kotlin/net/badgersmc/ek/infrastructure/restriction/RestrictionServiceTest.kt",
            "src/test/kotlin/net/badgersmc/ek/infrastructure/restriction/RestrictionListenerTest.kt",
            "src/test/kotlin/net/badgersmc/ek/infrastructure/protection/RegionProtectionListenerTest.kt",
        ),
        "durable payment journal and operational recovery" to listOf(
            "src/test/kotlin/net/badgersmc/ek/infrastructure/persistence/FilePaymentJournalTest.kt",
            "src/test/kotlin/net/badgersmc/ek/infrastructure/persistence/OperationalStateStoreTest.kt",
        ),
        "legacy statistics migration" to listOf(
            "src/test/kotlin/net/badgersmc/ek/infrastructure/persistence/LegacyStatsMigrationTest.kt",
        ),
        "configuration, commands, language and permissions" to listOf(
            "src/test/kotlin/net/badgersmc/ek/infrastructure/bukkit/ConfigSurfaceTest.kt",
            "src/test/kotlin/net/badgersmc/ek/infrastructure/i18n/LanguageAndPermissionContractTest.kt",
        ),
        "Discord delivery and placeholders" to listOf(
            "src/test/kotlin/net/badgersmc/ek/infrastructure/discord/DiscordDeliveryPolicyTest.kt",
            "src/test/kotlin/net/badgersmc/ek/infrastructure/papi/PlaceholderResolverTest.kt",
        ),
        "plugin compatibility surface" to listOf(
            "src/test/kotlin/net/badgersmc/ek/PluginCompatibilityTest.kt",
        ),
    )

    private fun repositoryRoot(): Path {
        val current = Path.of("").toAbsolutePath().normalize()
        if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) return current
        val parent = current.parent
        if (parent != null && Files.isRegularFile(parent.resolve("settings.gradle.kts"))) return parent
        error("Could not locate EnthusiaKOTH repository root from $current")
    }
}

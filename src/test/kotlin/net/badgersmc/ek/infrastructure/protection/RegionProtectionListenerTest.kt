package net.badgersmc.ek.infrastructure.protection

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.junit.jupiter.api.Test

class RegionProtectionListenerTest {
    @Test
    fun `declared bypass permits direct player maintenance`() {
        val protection = mockk<RegionProtectionService>(relaxed = true)
        val lang = mockk<LangService>()
        every { lang.msg(any(), *anyVararg()) } returns Component.empty()
        val player = mockk<Player>(relaxed = true)
        every { player.hasPermission(RegionProtectionListener.PROTECTION_BYPASS_PERMISSION) } returns true
        val block = mockk<Block>(relaxed = true)
        val event = mockk<BlockBreakEvent>(relaxed = true)
        every { event.player } returns player
        every { event.block } returns block

        RegionProtectionListener(protection, lang).onBreak(event)

        verify(exactly = 0) { protection.isProtected(any<Location>()) }
        verify(exactly = 0) { event.isCancelled = true }
    }

    @Test
    fun `protected block break is denied without bypass`() {
        val protection = mockk<RegionProtectionService>()
        every { protection.isProtected(any()) } returns true
        val lang = mockk<LangService>()
        every { lang.msg(any(), *anyVararg()) } returns Component.empty()
        val player = mockk<Player>(relaxed = true)
        every { player.hasPermission(any<String>()) } returns false
        val block = mockk<Block>(relaxed = true)
        every { block.location } returns mockk(relaxed = true)
        every { block.blockData } returns mockk<BlockData>()
        val event = mockk<BlockBreakEvent>(relaxed = true)
        every { event.player } returns player
        every { event.block } returns block

        RegionProtectionListener(protection, lang).onBreak(event)

        verify { event.isCancelled = true }
    }
}

package com.enthusia.koth.infrastructure.listener;

import com.enthusia.koth.application.protection.KothRegionProtectionService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.kyori.adventure.text.Component;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

/** Enforces permanent terrain protection without altering Warzone Rotator's PvP rules. */
public final class KothRegionProtectionListener implements Listener {
    private static final Component PROTECTED_MESSAGE = Component.text("This KOTH arena is protected.");
    private final KothRegionProtectionService protection;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Listener adapter holds the shared protection service.")
    public KothRegionProtectionListener(KothRegionProtectionService protection) {
        this.protection = protection;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (protection.isProtected(event.getBlock().getLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(PROTECTED_MESSAGE);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (protection.isProtected(event.getBlockPlaced().getLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(PROTECTED_MESSAGE);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (isProtectedBucketTarget(event.getBlockClicked(), event.getBlockFace())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(PROTECTED_MESSAGE);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (protection.isProtected(event.getBlockClicked().getLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(PROTECTED_MESSAGE);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> protection.isProtected(block.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> protection.isProtected(block.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (crossesProtectedBoundary(event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (crossesProtectedBoundary(event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    private boolean isProtectedBucketTarget(Block clicked, org.bukkit.block.BlockFace face) {
        return protection.isProtected(clicked.getRelative(face).getLocation());
    }

    private boolean crossesProtectedBoundary(java.util.List<Block> blocks, org.bukkit.block.BlockFace direction) {
        return blocks.stream().anyMatch(block -> protection.isProtected(block.getLocation())
                || protection.isProtected(block.getRelative(direction).getLocation()));
    }
}

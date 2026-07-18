package com.enthusia.koth.infrastructure.display;

import com.enthusia.koth.application.ports.AnnouncementPort;
import com.enthusia.koth.domain.event.ActiveEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Optional;

public final class BukkitAnnouncementAdapter implements AnnouncementPort {
    @Override
    public void announceStarting(ActiveEvent event) {
        send(event, Component.text("KOTH " + event.request().family().key() + " starting soon.", NamedTextColor.GOLD));
    }

    @Override
    public void announceStarted(ActiveEvent event) {
        send(event, Component.text("KOTH " + event.request().family().key() + " is active.", NamedTextColor.RED));
    }

    @Override
    public void announceProgress(ActiveEvent event) {
        send(event, Component.text("KOTH top: " + event.scores().entrySet().stream().limit(3).toList(), NamedTextColor.YELLOW));
    }

    @Override
    public void announceEnded(ActiveEvent event, Optional<String> winner) {
        send(event, Component.text("KOTH ended. Winner: " + winner.orElse("none"), NamedTextColor.GREEN));
    }

    private void send(ActiveEvent event, Component message) {
        if (!event.isPrivateTest()) {
            Bukkit.broadcast(message);
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (event.isParticipant(player.getUniqueId())) {
                player.sendMessage(message);
            }
        }
    }
}

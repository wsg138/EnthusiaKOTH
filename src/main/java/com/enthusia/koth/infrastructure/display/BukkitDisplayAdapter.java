package com.enthusia.koth.infrastructure.display;

import com.enthusia.koth.application.ports.DisplayPort;
import com.enthusia.koth.domain.event.ActiveEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;

public final class BukkitDisplayAdapter implements DisplayPort {
    @Override
    public void tick(ActiveEvent event) {
        Component action = Component.text("KOTH " + event.request().family().key() + " | Controller: "
                + event.currentController().map(Object::toString).orElse("contested"), NamedTextColor.GOLD);
        Bukkit.getOnlinePlayers().stream()
                .filter(player -> event.arena().zone().contains(player.getLocation()))
                .forEach(player -> player.sendActionBar(action));
    }

    @Override
    public void clear() {
        // Action bar messages expire naturally; there is no persistent display state to clear.
    }
}

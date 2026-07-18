package com.enthusia.koth.infrastructure.display;

import com.enthusia.koth.application.ports.DisplayPort;
import com.enthusia.koth.application.config.ConfigurationService;
import com.enthusia.koth.domain.event.ActiveEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

public final class BukkitDisplayAdapter implements DisplayPort {
    private final ConfigurationService configuration;

    public BukkitDisplayAdapter(ConfigurationService configuration) {
        this.configuration = configuration;
    }

    @Override
    public void tick(ActiveEvent event) {
        Component action = Component.text("KOTH " + event.request().family().key() + " | Controller: "
                + event.currentController().map(Object::toString).orElse("contested"), NamedTextColor.GOLD);
        Bukkit.getOnlinePlayers().stream()
                .filter(player -> event.isParticipant(player.getUniqueId()))
                .filter(player -> event.arena().zone().contains(player.getLocation()))
                .forEach(player -> player.sendActionBar(action));
        if (event.isPrivateTest() && configuration.settings().privateTesting().showObjectiveParticles()) {
            showPrivateObjective(event);
        }
    }

    @Override
    public void clear() {
        // Action bar messages expire naturally; there is no persistent display state to clear.
    }

    private void showPrivateObjective(ActiveEvent event) {
        var point = event.objectivePosition().toLocation();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (event.isParticipant(player.getUniqueId())) {
                player.spawnParticle(Particle.END_ROD, point, 4, 0.25, 0.35, 0.25, 0.01);
            }
        }
    }
}

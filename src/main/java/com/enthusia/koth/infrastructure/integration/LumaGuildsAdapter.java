package com.enthusia.koth.infrastructure.integration;

import com.enthusia.koth.application.ports.GuildPort;
import com.enthusia.koth.application.ports.TransactionResult;
import com.enthusia.koth.domain.TeamMode;
import com.enthusia.koth.domain.team.TeamId;
import com.enthusia.koth.domain.team.TeamSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class LumaGuildsAdapter implements GuildPort {
    @Override
    public boolean isAvailable() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("LumaGuilds");
        return plugin != null && plugin.isEnabled();
    }

    @Override
    public Optional<TeamSnapshot> playerGuild(Player player) {
        Object service = service("getGuildService");
        if (service == null) {
            return Optional.empty();
        }
        Object guilds = call(service, "getPlayerGuilds", new Class<?>[]{UUID.class}, player.getUniqueId());
        if (!(guilds instanceof Set<?> set) || set.isEmpty()) {
            return Optional.empty();
        }
        return snapshot(set.iterator().next());
    }

    @Override
    public Optional<TeamSnapshot> guild(UUID guildId) {
        Object service = service("getGuildService");
        if (service == null) {
            return Optional.empty();
        }
        return snapshot(call(service, "getGuild", new Class<?>[]{UUID.class}, guildId));
    }

    @Override
    public TransactionResult depositGuildReward(UUID guildId, double amount, String reason) {
        Object guild = guildObject(guildId);
        Object vault = service("getGuildVaultService");
        if (guild == null || vault == null) {
            return TransactionResult.failure("LumaGuilds guild/vault service unavailable.");
        }
        Object result = call(vault, "depositToVault", new Class<?>[]{guild.getClass(), double.class, String.class}, guild, amount, reason);
        if (result == null) {
            return TransactionResult.failure("LumaGuilds did not return a vault result.");
        }
        if (result.getClass().getSimpleName().equals("Success")) {
            return TransactionResult.success("Guild vault reward deposited.");
        }
        Object message = call(result, "getMessage", new Class<?>[]{});
        if (message == null) {
            message = call(result, "getMessageOrNull", new Class<?>[]{});
        }
        return TransactionResult.failure(message == null ? "Guild vault deposit failed." : message.toString());
    }

    private Object guildObject(UUID guildId) {
        Object service = service("getGuildService");
        return service == null ? null : call(service, "getGuild", new Class<?>[]{UUID.class}, guildId);
    }

    private Optional<TeamSnapshot> snapshot(Object guild) {
        if (guild == null) {
            return Optional.empty();
        }
        UUID id = (UUID) call(guild, "getId", new Class<?>[]{});
        String name = String.valueOf(call(guild, "getName", new Class<?>[]{}));
        Object tag = call(guild, "getTag", new Class<?>[]{});
        Object banner = call(guild, "getBanner", new Class<?>[]{});
        return Optional.of(new TeamSnapshot(
                new TeamId(TeamMode.GUILD, id),
                tag == null || tag.toString().isBlank() ? name : tag.toString(),
                banner instanceof ItemStack item ? Optional.of(item) : Optional.empty(),
                Set.of()
        ));
    }

    private Object service(String getter) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("LumaGuilds");
        if (plugin == null || !plugin.isEnabled()) {
            return null;
        }
        return call(plugin, getter, new Class<?>[]{});
    }

    private Object call(Object target, String method, Class<?>[] parameterTypes, Object... args) {
        try {
            Method reflected = target.getClass().getMethod(method, parameterTypes);
            return reflected.invoke(target, args);
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }
}

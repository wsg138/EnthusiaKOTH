package com.enthusia.koth.domain.team;

import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record TeamSnapshot(TeamId id, String displayName, Optional<ItemStack> banner, Set<UUID> onlineMembers) {
}

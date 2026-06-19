package com.enthusia.koth.domain;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public record Position(String world, double x, double y, double z) {
    public Location toLocation() {
        World bukkitWorld = Bukkit.getWorld(world);
        if (bukkitWorld == null) {
            throw new IllegalStateException("World is not loaded: " + world);
        }
        return new Location(bukkitWorld, x, y, z);
    }
}

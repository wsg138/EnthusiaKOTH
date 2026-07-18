package com.enthusia.koth.domain;

import org.bukkit.Location;

/** Permanent horizontal protection boundary for a KOTH arena. */
public record KothRegion(String id, Position center, double radius) {
    public boolean contains(Location location) {
        if (location.getWorld() == null || !location.getWorld().getName().equals(center.world())) {
            return false;
        }
        double dx = location.getX() - center.x();
        double dz = location.getZ() - center.z();
        return (dx * dx) + (dz * dz) <= radius * radius;
    }
}

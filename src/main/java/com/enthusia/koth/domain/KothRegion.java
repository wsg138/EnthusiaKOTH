package com.enthusia.koth.domain;

import org.bukkit.Location;

/** Permanent cuboid protection boundary for a KOTH arena. */
public record KothRegion(String id, Position first, Position second) {
    public boolean contains(Location location) {
        if (location.getWorld() == null || !location.getWorld().getName().equals(first.world())) {
            return false;
        }
        return between(location.getX(), first.x(), second.x())
                && between(location.getY(), first.y(), second.y())
                && between(location.getZ(), first.z(), second.z());
    }

    private boolean between(double value, double firstValue, double secondValue) {
        return value >= Math.min(firstValue, secondValue) && value <= Math.max(firstValue, secondValue);
    }
}

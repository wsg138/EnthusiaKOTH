package com.enthusia.koth.domain;

import org.bukkit.Location;

public record CaptureZone(String id, Position center, double radius) {
    public boolean contains(Location location) {
        if (!location.getWorld().getName().equals(center.world())) {
            return false;
        }
        double dx = location.getX() - center.x();
        double dz = location.getZ() - center.z();
        return (dx * dx) + (dz * dz) <= radius * radius && Math.abs(location.getY() - center.y()) <= 8.0;
    }
}

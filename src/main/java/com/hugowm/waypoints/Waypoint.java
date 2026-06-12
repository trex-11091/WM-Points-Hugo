package com.hugowm.waypoints;

import net.minecraft.client.network.ServerInfo;
import net.minecraft.util.math.BlockPos;

import java.util.Locale;

public record Waypoint(
    String name,
    String server,
    String dimension,
    int x,
    int y,
    int z,
    int color
) {
    public BlockPos blockPos() {
        return new BlockPos(x, y, z);
    }

    public String id() {
        return normalize(name) + "|" + x + "|" + y + "|" + z;
    }

    public boolean matchesDimension(String currentDimension) {
        return dimension == null || dimension.isBlank() || dimension.equals(currentDimension);
    }

    public boolean matchesServer(ServerInfo serverInfo) {
        if (server == null || server.isBlank()) {
            return true;
        }

        if (serverInfo == null || serverInfo.address == null || serverInfo.address.isBlank()) {
            return false;
        }

        String expected = normalizeServer(server);
        String current = normalizeServer(serverInfo.address);
        return current.equals(expected) || current.endsWith("." + expected);
    }

    public float red() {
        return ((color >> 16) & 0xFF) / 255.0F;
    }

    public float green() {
        return ((color >> 8) & 0xFF) / 255.0F;
    }

    public float blue() {
        return (color & 0xFF) / 255.0F;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeServer(String value) {
        String normalized = normalize(value);
        if (normalized.startsWith("mp:")) {
            normalized = normalized.substring(3);
        }

        int portIndex = normalized.lastIndexOf(':');
        if (portIndex >= 0) {
            String suffix = normalized.substring(portIndex + 1);
            if (suffix.chars().allMatch(Character::isDigit)) {
                normalized = normalized.substring(0, portIndex);
            }
        }

        return normalized;
    }
}

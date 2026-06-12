package com.hugowm.waypoints;

import java.util.ArrayList;
import java.util.List;

public final class WaypointConfig {
    public double reachRadius = 2.0D;
    public List<Entry> waypoints = new ArrayList<>();

    public List<Waypoint> toWaypoints() {
        List<Waypoint> loadedWaypoints = new ArrayList<>();

        for (Entry entry : waypoints) {
            if (entry == null || entry.name == null || entry.name.isBlank()) {
                continue;
            }

            loadedWaypoints.add(new Waypoint(
                entry.name,
                entry.server == null ? "" : entry.server,
                entry.dimension == null || entry.dimension.isBlank() ? "minecraft:overworld" : entry.dimension,
                entry.x,
                entry.y,
                entry.z,
                entry.color
            ));
        }

        return loadedWaypoints;
    }

    public static final class Entry {
        public String name = "";
        public String server = "";
        public String dimension = "minecraft:overworld";
        public int x;
        public int y;
        public int z;
        public int color = 5635925;
    }
}

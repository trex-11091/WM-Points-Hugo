# Hugo WM Waypoints

A standalone Fabric client mod for Minecraft 1.21.11 that shows imported waypoint locations in the world and removes them automatically once the player reaches them.

## Features

- Standalone waypoint rendering without requiring any extra waypoint mod
- HUD list with direction arrows and distances
- Strong world markers with block highlight, floor marker, and vertical beam
- Automatic completion when the player reaches a waypoint
- Player-specific progress files per server

## Project Files

- `src/main/java/com/hugowm/waypoints/WaypointClientMod.java`: main mod logic
- `src/main/java/com/hugowm/waypoints/Waypoint.java`: waypoint model and ID logic
- `src/main/java/com/hugowm/waypoints/WaypointConfig.java`: config loading
- `src/main/resources/default-waypoints.json`: bundled waypoint list

## Build

```bash
./gradlew clean build
```

Built jars:

- `build/libs/Hugo-wm-waypoints.jar`
- `build/libs/Hugo-wm-waypoints-sources.jar`

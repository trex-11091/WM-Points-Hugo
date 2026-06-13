package com.hugowm.waypoints;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.render.debug.DebugRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.waypoint.TrackedWaypoint;
import net.minecraft.world.waypoint.WaypointStyles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

public final class WaypointClientMod implements ClientModInitializer {
    public static final String MOD_ID = "standalonewaypoints";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
    private static final Path WAYPOINT_FILE = CONFIG_DIR.resolve("waypoints.json");
    private static final Path SETTINGS_FILE = CONFIG_DIR.resolve("settings.json");
    private static final Path PROGRESS_DIR = CONFIG_DIR.resolve("progress");
    private static final int DEFAULT_MENU_KEY_CODE = GLFW.GLFW_KEY_V;
    private static final float BASE_BEAM_RED = 0.20F;
    private static final float BASE_BEAM_GREEN = 1.00F;
    private static final float BASE_BEAM_BLUE = 0.38F;

    private static WaypointClientMod instance;

    private double reachRadius = 2.0D;
    private List<Waypoint> waypoints = List.of();
    private boolean modEnabled = true;
    private int menuKeyCode = DEFAULT_MENU_KEY_CODE;
    private boolean menuKeyWasDown;
    private final Set<String> completedWaypointIds = new HashSet<>();
    private final Set<String> trackedWaypointIds = new HashSet<>();
    private ClientPlayNetworkHandler lastNetworkHandler;
    private Path currentCompletedFile;
    private String currentCompletedFileKey = "";

    public static WaypointClientMod getInstance() {
        return instance;
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        loadSettings();
        reloadFromDisk();
    }

    public void onClientTick(MinecraftClient client) {
        handleMenuKey(client);

        if (client.player == null || client.world == null) {
            clearTrackedWaypoints(client);
            currentCompletedFile = null;
            currentCompletedFileKey = "";
            return;
        }

        ensureCompletedWaypointContext(client);

        if (!modEnabled) {
            clearTrackedWaypoints(client);
            return;
        }

        String currentDimension = client.world.getRegistryKey().getValue().toString();
        ServerInfo serverInfo = client.getCurrentServerEntry();
        BlockPos playerPos = client.player.getBlockPos();
        double maxSquaredDistance = reachRadius * reachRadius;

        List<Waypoint> reachedWaypoints = new ArrayList<>();
        for (Waypoint waypoint : waypoints) {
            if (completedWaypointIds.contains(waypoint.id())) {
                continue;
            }

            if (!waypoint.matchesDimension(currentDimension) || !waypoint.matchesServer(serverInfo)) {
                continue;
            }

            if (playerPos.getSquaredDistance(waypoint.blockPos()) <= maxSquaredDistance) {
                reachedWaypoints.add(waypoint);
            }
        }

        if (reachedWaypoints.isEmpty()) {
            List<WaypointDistance> activeWaypoints = getCurrentWaypoints(client);
            syncTrackedWaypoints(client, activeWaypoints);
            return;
        }

        boolean changed = false;
        for (Waypoint waypoint : reachedWaypoints) {
            if (completedWaypointIds.add(waypoint.id())) {
                changed = true;
                client.player.sendMessage(
                    Text.literal("[Waypoints] ")
                        .formatted(Formatting.AQUA, Formatting.BOLD)
                        .append(Text.literal(waypoint.name() + " erreicht - ausgeblendet.").formatted(Formatting.GREEN)),
                    false
                );
            }
        }

        if (changed) {
            saveCompletedWaypoints();
        }

        List<WaypointDistance> activeWaypoints = getCurrentWaypoints(client);
        syncTrackedWaypoints(client, activeWaypoints);
    }

    public void renderHud(DrawContext drawContext) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!modEnabled || client.player == null || client.world == null || client.textRenderer == null) {
            return;
        }

        List<WaypointDistance> activeWaypoints = getCurrentWaypoints(client);
        if (activeWaypoints.isEmpty()) {
            return;
        }

        int panelLeft = 8;
        int panelTop = 8;
        int lineHeight = 11;
        drawContext.drawText(
            client.textRenderer,
            "Waypoints " + activeWaypoints.size() + "/" + activeWaypoints.size(),
            panelLeft,
            panelTop,
            0xFFFFFF,
            true
        );

        int lineY = panelTop + 14;
        for (int i = 0; i < activeWaypoints.size(); i++) {
            WaypointDistance waypointDistance = activeWaypoints.get(i);
            Waypoint waypoint = waypointDistance.waypoint();
            String line = directionArrow(client, waypoint) + " " + waypoint.name() + " [" + Math.round(waypointDistance.distance()) + "m]";
            int color = i == 0 ? 0x66FF66 : waypoint.color();
            drawContext.drawText(client.textRenderer, line, panelLeft, lineY, color, true);
            lineY += lineHeight;
        }
    }

    private void syncTrackedWaypoints(MinecraftClient client, List<WaypointDistance> activeWaypoints) {
        if (client.player == null || client.world == null) {
            return;
        }

        ClientPlayNetworkHandler networkHandler = client.player.networkHandler;
        if (networkHandler == null) {
            trackedWaypointIds.clear();
            lastNetworkHandler = null;
            return;
        }

        if (networkHandler != lastNetworkHandler) {
            trackedWaypointIds.clear();
            lastNetworkHandler = networkHandler;
        }

        Set<String> activeIds = new HashSet<>();
        for (WaypointDistance waypointDistance : activeWaypoints) {
            Waypoint waypoint = waypointDistance.waypoint();
            String waypointId = waypoint.id();
            activeIds.add(waypointId);

            if (trackedWaypointIds.add(waypointId)) {
                networkHandler.getWaypointHandler().onTrack(createTrackedWaypoint(waypoint));
            }
        }

        Set<String> idsToRemove = new HashSet<>(trackedWaypointIds);
        idsToRemove.removeAll(activeIds);
        for (String waypointId : idsToRemove) {
            trackedWaypointIds.remove(waypointId);
            networkHandler.getWaypointHandler().onUntrack(TrackedWaypoint.empty(uuidForWaypointId(waypointId)));
        }
    }

    public void renderWorldWaypoints(MatrixStack matrices, VertexConsumerProvider.Immediate vertexConsumers, double cameraX, double cameraY, double cameraZ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!modEnabled || client.player == null || client.world == null) {
            return;
        }

        List<WaypointDistance> activeWaypoints = getCurrentWaypoints(client);
        if (activeWaypoints.isEmpty()) {
            return;
        }

        double beamTopY = client.world.getTopYInclusive() + 8.0D;
        VertexConsumer lineConsumer = vertexConsumers.getBuffer(RenderLayer.getLines());
        for (int i = 0; i < activeWaypoints.size(); i++) {
            renderWorldWaypoint(
                matrices,
                vertexConsumers,
                lineConsumer,
                activeWaypoints.get(i).waypoint(),
                cameraX,
                cameraY,
                cameraZ,
                beamTopY,
                i == 0
            );
        }
    }

    private void renderWorldWaypoint(
        MatrixStack matrices,
        VertexConsumerProvider vertexConsumers,
        VertexConsumer lineConsumer,
        Waypoint waypoint,
        double cameraX,
        double cameraY,
        double cameraZ,
        double beamTopY,
        boolean primaryWaypoint
    ) {
        float[] beamColor = getBeamColor(waypoint.color(), primaryWaypoint);
        double centerX = waypoint.x() + 0.5D;
        double centerY = waypoint.y();
        double centerZ = waypoint.z() + 0.5D;
        double relativeX = centerX - cameraX;
        double relativeY = centerY - cameraY;
        double relativeZ = centerZ - cameraZ;
        double beamBaseY = relativeY + 0.05D;
        double beamTop = beamTopY - cameraY;
        double outerRadius = primaryWaypoint ? 0.055D : 0.042D;
        double coreRadius = primaryWaypoint ? 0.021D : 0.016D;
        double ringRadius = primaryWaypoint ? 0.48D : 0.38D;
        double ringHeight = primaryWaypoint ? 0.10D : 0.08D;

        DebugRenderer.drawBox(
            matrices,
            vertexConsumers,
            new Box(
                relativeX - outerRadius,
                beamBaseY,
                relativeZ - outerRadius,
                relativeX + outerRadius,
                beamTop,
                relativeZ + outerRadius
            ),
            beamColor[0],
            beamColor[1],
            beamColor[2],
            primaryWaypoint ? 0.18F : 0.12F
        );
        DebugRenderer.drawBox(
            matrices,
            vertexConsumers,
            new Box(
                relativeX - coreRadius,
                beamBaseY,
                relativeZ - coreRadius,
                relativeX + coreRadius,
                beamTop,
                relativeZ + coreRadius
            ),
            beamColor[0],
            beamColor[1],
            beamColor[2],
            primaryWaypoint ? 0.90F : 0.76F
        );

        DebugRenderer.drawBox(
            matrices,
            vertexConsumers,
            new Box(
                relativeX - ringRadius,
                relativeY + 0.01D,
                relativeZ - ringRadius,
                relativeX + ringRadius,
                relativeY + ringHeight,
                relativeZ + ringRadius
            ),
            beamColor[0],
            beamColor[1],
            beamColor[2],
            primaryWaypoint ? 0.24F : 0.18F
        );

        DebugRenderer.drawBox(
            matrices,
            vertexConsumers,
            new Box(
                relativeX - 0.18D,
                relativeY + 0.10D,
                relativeZ - 0.18D,
                relativeX + 0.18D,
                relativeY + 0.22D,
                relativeZ + 0.18D
            ),
            beamColor[0],
            beamColor[1],
            beamColor[2],
            primaryWaypoint ? 0.78F : 0.64F
        );

        VertexRendering.drawBox(
            matrices.peek(),
            lineConsumer,
            relativeX - ringRadius,
            relativeY + 0.01D,
            relativeZ - ringRadius,
            relativeX + ringRadius,
            relativeY + ringHeight,
            relativeZ + ringRadius,
            beamColor[0],
            beamColor[1],
            beamColor[2],
            primaryWaypoint ? 1.0F : 0.88F
        );
        VertexRendering.drawBox(
            matrices.peek(),
            lineConsumer,
            relativeX - 0.50D,
            relativeY + 0.02D,
            relativeZ - 0.50D,
            relativeX + 0.50D,
            relativeY + 1.02D,
            relativeZ + 0.50D,
            beamColor[0],
            beamColor[1],
            beamColor[2],
            primaryWaypoint ? 0.92F : 0.78F
        );
    }

    private float[] getBeamColor(int color, boolean primaryWaypoint) {
        float red = ((color >> 16) & 0xFF) / 255.0F;
        float green = ((color >> 8) & 0xFF) / 255.0F;
        float blue = (color & 0xFF) / 255.0F;
        red = Math.max(red, BASE_BEAM_RED);
        green = Math.max(green, BASE_BEAM_GREEN);
        blue = Math.max(blue, BASE_BEAM_BLUE);

        if (primaryWaypoint) {
            red = Math.min(1.0F, red + 0.08F);
            blue = Math.min(1.0F, blue + 0.05F);
        }

        return new float[] {red, green, blue};
    }

    public void reloadFromDisk() {
        try {
            Files.createDirectories(CONFIG_DIR);
            Files.createDirectories(PROGRESS_DIR);
            ensureWaypointFileExists();
            loadWaypointFile();
            completedWaypointIds.clear();
        } catch (IOException exception) {
            LOGGER.error("Could not initialize waypoint config", exception);
            WaypointConfig fallbackConfig = loadBundledDefaults();
            this.reachRadius = fallbackConfig.reachRadius;
            this.waypoints = fallbackConfig.toWaypoints();
            this.completedWaypointIds.clear();
        }
    }

    public boolean isModEnabled() {
        return modEnabled;
    }

    public void setModEnabled(boolean modEnabled) {
        this.modEnabled = modEnabled;
        saveSettings();

        if (!modEnabled) {
            clearTrackedWaypoints(MinecraftClient.getInstance());
        }
    }

    public int getMenuKeyCode() {
        return menuKeyCode;
    }

    public void setMenuKeyCode(int menuKeyCode) {
        this.menuKeyCode = menuKeyCode <= GLFW.GLFW_KEY_UNKNOWN ? DEFAULT_MENU_KEY_CODE : menuKeyCode;
        saveSettings();
    }

    public Text getMenuKeyText() {
        return InputUtil.fromKeyCode(new KeyInput(menuKeyCode, 0, 0)).getLocalizedText();
    }

    public void restartBeams() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null && client.world != null) {
            ensureCompletedWaypointContext(client);
        }

        completedWaypointIds.clear();
        saveCompletedWaypoints();
        clearTrackedWaypoints(client);

        if (client == null || client.player == null || client.world == null) {
            return;
        }

        if (modEnabled) {
            syncTrackedWaypoints(client, getCurrentWaypoints(client));
        }

        client.player.sendMessage(
            Text.literal("[Waypoints] ")
                .formatted(Formatting.AQUA, Formatting.BOLD)
                .append(Text.literal("Alle Strahlen wurden zurückgesetzt.").formatted(Formatting.GREEN)),
            false
        );
    }

    private void ensureWaypointFileExists() throws IOException {
        if (Files.exists(WAYPOINT_FILE)) {
            return;
        }

        WaypointConfig bundledDefaults = loadBundledDefaults();
        writeWaypointFile(bundledDefaults);
    }

    private void loadWaypointFile() throws IOException {
        try (Reader reader = Files.newBufferedReader(WAYPOINT_FILE)) {
            WaypointConfig config = GSON.fromJson(reader, WaypointConfig.class);
            if (config == null) {
                config = loadBundledDefaults();
            }

            boolean corrected = applyBundledCorrections(config);
            if (corrected) {
                writeWaypointFile(config);
            }

            this.reachRadius = Math.max(1.0D, config.reachRadius);
            this.waypoints = List.copyOf(config.toWaypoints());
        }
    }

    private void loadSettings() {
        try {
            Files.createDirectories(CONFIG_DIR);
            if (!Files.exists(SETTINGS_FILE)) {
                saveSettings();
                return;
            }

            try (Reader reader = Files.newBufferedReader(SETTINGS_FILE)) {
                ModSettings settings = GSON.fromJson(reader, ModSettings.class);
                if (settings == null) {
                    saveSettings();
                    return;
                }

                modEnabled = settings.modEnabled;
                menuKeyCode = settings.menuKeyCode <= GLFW.GLFW_KEY_UNKNOWN ? DEFAULT_MENU_KEY_CODE : settings.menuKeyCode;
            }
        } catch (IOException exception) {
            LOGGER.error("Could not load waypoint settings", exception);
            modEnabled = true;
            menuKeyCode = DEFAULT_MENU_KEY_CODE;
        }
    }

    private void saveSettings() {
        try {
            Files.createDirectories(CONFIG_DIR);
            try (Writer writer = Files.newBufferedWriter(SETTINGS_FILE)) {
                ModSettings settings = new ModSettings();
                settings.modEnabled = modEnabled;
                settings.menuKeyCode = menuKeyCode;
                GSON.toJson(settings, writer);
            }
        } catch (IOException exception) {
            LOGGER.error("Could not save waypoint settings", exception);
        }
    }

    private boolean applyBundledCorrections(WaypointConfig config) {
        if (config == null || config.waypoints == null) {
            return false;
        }

        boolean corrected = false;
        for (WaypointConfig.Entry entry : config.waypoints) {
            if (entry == null || entry.name == null) {
                continue;
            }

            if (entry.name.equalsIgnoreCase("Ball #3")
                && entry.x == -189
                && entry.y == 79
                && entry.z == -187) {
                entry.x = -108;
                entry.y = 79;
                entry.z = -103;
                corrected = true;
            }

            if (entry.name.equalsIgnoreCase("NewaBall #49")
                && entry.x == -51
                && entry.y == 76
                && entry.z == 70) {
                entry.name = "Ball #49";
                corrected = true;
            }
        }

        return corrected;
    }

    private void writeWaypointFile(WaypointConfig config) throws IOException {
        try (Writer writer = Files.newBufferedWriter(WAYPOINT_FILE)) {
            GSON.toJson(config, writer);
        }
    }

    private void handleMenuKey(MinecraftClient client) {
        if (client == null || client.getWindow() == null) {
            return;
        }

        boolean menuKeyDown = InputUtil.isKeyPressed(client.getWindow(), menuKeyCode);
        if (client.currentScreen instanceof WaypointSettingsScreen) {
            menuKeyWasDown = menuKeyDown;
            return;
        }

        if (menuKeyDown && !menuKeyWasDown && client.currentScreen == null) {
            client.setScreen(new WaypointSettingsScreen(this, null));
        }

        menuKeyWasDown = menuKeyDown;
    }

    private void clearTrackedWaypoints(MinecraftClient client) {
        ClientPlayNetworkHandler networkHandler = null;
        if (client != null && client.player != null) {
            networkHandler = client.player.networkHandler;
        }

        if (networkHandler != null) {
            for (String waypointId : new HashSet<>(trackedWaypointIds)) {
                networkHandler.getWaypointHandler().onUntrack(TrackedWaypoint.empty(uuidForWaypointId(waypointId)));
            }
        }

        trackedWaypointIds.clear();
        lastNetworkHandler = networkHandler;
    }

    private void loadCompletedWaypoints() throws IOException {
        completedWaypointIds.clear();
        if (currentCompletedFile == null || !Files.exists(currentCompletedFile)) {
            return;
        }

        boolean migrated = false;
        try (Reader reader = Files.newBufferedReader(currentCompletedFile)) {
            CompletedWaypoints completedWaypoints = GSON.fromJson(reader, CompletedWaypoints.class);
            if (completedWaypoints != null && completedWaypoints.completedIds != null) {
                for (String completedId : completedWaypoints.completedIds) {
                    String normalizedId = normalizeCompletedWaypointId(completedId);
                    if (!normalizedId.equals(completedId)) {
                        migrated = true;
                    }
                    completedWaypointIds.add(normalizedId);
                }
            }
        }

        if (migrated) {
            saveCompletedWaypoints();
        }
    }

    private void saveCompletedWaypoints() {
        if (currentCompletedFile == null) {
            return;
        }

        CompletedWaypoints completedWaypoints = new CompletedWaypoints();
        completedWaypoints.completedIds.addAll(completedWaypointIds);

        try {
            Files.createDirectories(currentCompletedFile.getParent());
        } catch (IOException exception) {
            LOGGER.error("Could not create waypoint progress directory", exception);
            return;
        }

        try (Writer writer = Files.newBufferedWriter(currentCompletedFile)) {
            GSON.toJson(completedWaypoints, writer);
        } catch (IOException exception) {
            LOGGER.error("Could not save completed waypoints", exception);
        }
    }

    private void ensureCompletedWaypointContext(MinecraftClient client) {
        Path completedFile = resolveCompletedFile(client);
        String fileKey = completedFile.toString();
        if (fileKey.equals(currentCompletedFileKey)) {
            return;
        }

        currentCompletedFile = completedFile;
        currentCompletedFileKey = fileKey;
        trackedWaypointIds.clear();

        try {
            Files.createDirectories(PROGRESS_DIR);
            loadCompletedWaypoints();
        } catch (IOException exception) {
            LOGGER.error("Could not load player-specific waypoint progress", exception);
            completedWaypointIds.clear();
        }
    }

    private Path resolveCompletedFile(MinecraftClient client) {
        String username = sanitizeFilePart(client.getSession().getUsername());
        ServerInfo serverInfo = client.getCurrentServerEntry();
        String server = serverInfo == null ? "singleplayer" : sanitizeFilePart(serverInfo.address);
        return PROGRESS_DIR.resolve(server + "__" + username + ".json");
    }

    private WaypointConfig loadBundledDefaults() {
        InputStream resourceStream = WaypointClientMod.class.getResourceAsStream("/default-waypoints.json");
        if (resourceStream == null) {
            LOGGER.error("Bundled default-waypoints.json is missing");
            return new WaypointConfig();
        }

        try (Reader reader = new InputStreamReader(resourceStream, StandardCharsets.UTF_8)) {
            WaypointConfig config = GSON.fromJson(reader, WaypointConfig.class);
            if (config != null) {
                return config;
            }
        } catch (Exception exception) {
            LOGGER.error("Could not read bundled default waypoints", exception);
        }

        return new WaypointConfig();
    }

    private List<WaypointDistance> getCurrentWaypoints(MinecraftClient client) {
        String currentDimension = client.world.getRegistryKey().getValue().toString();
        ServerInfo serverInfo = client.getCurrentServerEntry();
        List<WaypointDistance> currentWaypoints = new ArrayList<>();

        for (Waypoint waypoint : waypoints) {
            if (completedWaypointIds.contains(waypoint.id())) {
                continue;
            }

            if (!waypoint.matchesDimension(currentDimension) || !waypoint.matchesServer(serverInfo)) {
                continue;
            }

            double squaredDistance = client.player.squaredDistanceTo(
                waypoint.x() + 0.5D,
                waypoint.y() + 0.5D,
                waypoint.z() + 0.5D
            );
            currentWaypoints.add(new WaypointDistance(waypoint, Math.sqrt(squaredDistance), squaredDistance));
        }

        currentWaypoints.sort(Comparator.comparingDouble(WaypointDistance::distanceSquared));
        return currentWaypoints;
    }

    private TrackedWaypoint createTrackedWaypoint(Waypoint waypoint) {
        net.minecraft.world.waypoint.Waypoint.Config config = new net.minecraft.world.waypoint.Waypoint.Config();
        config.style = WaypointStyles.DEFAULT;
        config.color = Optional.of(waypoint.color());
        return TrackedWaypoint.ofPos(uuidForWaypointId(waypoint.id()), config, waypoint.blockPos());
    }

    private UUID uuidForWaypointId(String waypointId) {
        return UUID.nameUUIDFromBytes((MOD_ID + ":" + waypointId).getBytes(StandardCharsets.UTF_8));
    }

    private String sanitizeFilePart(String value) {
        String sanitized = value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
        return sanitized.isBlank() ? "default" : sanitized;
    }

    private String directionArrow(MinecraftClient client, Waypoint waypoint) {
        double dx = waypoint.x() + 0.5D - client.player.getX();
        double dz = waypoint.z() + 0.5D - client.player.getZ();
        double yawToWaypoint = Math.toDegrees(Math.atan2(dz, dx)) - 90.0D;
        double relativeYaw = MathHelper.wrapDegrees((float) (yawToWaypoint - client.player.getYaw(1.0F)));

        if (relativeYaw >= -22.5D && relativeYaw < 22.5D) {
            return "\u2191";
        }
        if (relativeYaw >= 22.5D && relativeYaw < 67.5D) {
            return "\u2197";
        }
        if (relativeYaw >= 67.5D && relativeYaw < 112.5D) {
            return "\u2192";
        }
        if (relativeYaw >= 112.5D && relativeYaw < 157.5D) {
            return "\u2198";
        }
        if (relativeYaw >= -67.5D && relativeYaw < -22.5D) {
            return "\u2196";
        }
        if (relativeYaw >= -112.5D && relativeYaw < -67.5D) {
            return "\u2190";
        }
        if (relativeYaw >= -157.5D && relativeYaw < -112.5D) {
            return "\u2199";
        }
        return "\u2193";
    }

    private String normalizeCompletedWaypointId(String waypointId) {
        if ("newaball #49|-51|76|70".equalsIgnoreCase(waypointId)) {
            return "ball #49|-51|76|70";
        }

        return waypointId;
    }

    private static final class CompletedWaypoints {
        private List<String> completedIds = new ArrayList<>();
    }

    private static final class ModSettings {
        private boolean modEnabled = true;
        private int menuKeyCode = DEFAULT_MENU_KEY_CODE;
    }

    private record WaypointDistance(Waypoint waypoint, double distance, double distanceSquared) {
    }
}

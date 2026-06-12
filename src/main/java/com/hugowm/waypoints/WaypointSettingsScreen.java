package com.hugowm.waypoints;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public final class WaypointSettingsScreen extends Screen {
    private final WaypointClientMod mod;
    private final Screen parent;

    private ButtonWidget toggleButton;
    private ButtonWidget keyButton;
    private boolean waitingForKey;

    public WaypointSettingsScreen(WaypointClientMod mod, Screen parent) {
        super(Text.literal("Hugo WM Waypoints"));
        this.mod = mod;
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int startY = height / 2 - 42;
        int buttonWidth = 220;
        int buttonHeight = 20;
        int left = centerX - buttonWidth / 2;

        toggleButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            mod.setModEnabled(!mod.isModEnabled());
            refreshButtons();
        }).dimensions(left, startY, buttonWidth, buttonHeight).build());

        keyButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            waitingForKey = true;
            refreshButtons();
        }).dimensions(left, startY + 26, buttonWidth, buttonHeight).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Taste auf V zurücksetzen"), button -> {
            waitingForKey = false;
            mod.setMenuKeyCode(GLFW.GLFW_KEY_V);
            refreshButtons();
        }).dimensions(left, startY + 52, buttonWidth, buttonHeight).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Schließen"), button -> close())
            .dimensions(left, startY + 96, buttonWidth, buttonHeight)
            .build());

        refreshButtons();
    }

    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, float delta) {
        drawContext.fill(0, 0, width, height, 0xAA101010);
        super.render(drawContext, mouseX, mouseY, delta);

        int centerX = width / 2;
        drawContext.drawCenteredTextWithShadow(textRenderer, title, centerX, height / 2 - 78, 0xFFFFFF);

        Text description = waitingForKey
            ? Text.literal("Drücke jetzt die neue Taste")
            : Text.literal("Standardtaste zum Öffnen: " + mod.getMenuKeyText().getString());
        drawContext.drawCenteredTextWithShadow(textRenderer, description, centerX, height / 2 - 60, 0xB8FFB8);

        String state = mod.isModEnabled() ? "Die Waypoints sind gerade AN" : "Die Waypoints sind gerade AUS";
        drawContext.drawCenteredTextWithShadow(textRenderer, state, centerX, height / 2 + 82, 0xD8D8D8);
    }

    @Override
    public boolean keyPressed(KeyInput keyInput) {
        if (waitingForKey) {
            if (keyInput.getKeycode() == GLFW.GLFW_KEY_ESCAPE) {
                waitingForKey = false;
                refreshButtons();
                return true;
            }

            mod.setMenuKeyCode(keyInput.getKeycode());
            waitingForKey = false;
            refreshButtons();
            return true;
        }

        return super.keyPressed(keyInput);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    private void refreshButtons() {
        if (toggleButton != null) {
            toggleButton.setMessage(Text.literal("Mod: " + (mod.isModEnabled() ? "AN" : "AUS")));
        }

        if (keyButton != null) {
            keyButton.setMessage(waitingForKey
                ? Text.literal("Neue Taste drücken...")
                : Text.literal("Menü-Taste: " + mod.getMenuKeyText().getString()));
        }
    }
}

package com.hugowm.waypoints.mixin;

import com.hugowm.waypoints.WaypointClientMod;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.debug.DebugRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DebugRenderer.class)
public abstract class DebugRendererMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void standalonewaypoints$renderWorldWaypoints(
        MatrixStack matrices,
        Frustum frustum,
        VertexConsumerProvider.Immediate vertexConsumers,
        double cameraX,
        double cameraY,
        double cameraZ,
        boolean late,
        CallbackInfo ci
    ) {
        if (!late) {
            return;
        }

        WaypointClientMod.getInstance().renderWorldWaypoints(matrices, vertexConsumers, cameraX, cameraY, cameraZ);
    }
}

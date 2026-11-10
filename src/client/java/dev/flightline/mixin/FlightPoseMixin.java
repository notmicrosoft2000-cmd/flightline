package dev.flightline.mixin;

import dev.flightline.client.FlightLineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AvatarRenderer.class)
public abstract class FlightPoseMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"))
    private void flightline$useGlidingPose(Avatar avatar, AvatarRenderState state, float partialTick,
                                           CallbackInfo callbackInfo) {
        Minecraft client = Minecraft.getInstance();
        if (avatar == client.player && FlightLineClient.isAirborne()) {
            state.isFallFlying = true;
            state.fallFlyingTimeInTicks = Math.max(1.0f, state.fallFlyingTimeInTicks);
        }
    }
}

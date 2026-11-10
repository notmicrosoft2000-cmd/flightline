package dev.flightline.mixin;

import dev.flightline.client.FlightLineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Entity.class)
public abstract class FlightHeadLockMixin {
    @ModifyVariable(method = "turn(DD)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private double flightline$ignoreMouseYaw(double yawDelta) {
        Minecraft client = Minecraft.getInstance();
        if (FlightLineClient.isFlightEnabled() && (Object) this == client.player) return 0.0;
        return yawDelta;
    }
}

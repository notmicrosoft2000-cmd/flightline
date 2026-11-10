package dev.flightline.mixin;

import dev.flightline.flight.FlightController;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class FlightLandingMixin {
    @Inject(method = "causeFallDamage", at = @At("HEAD"), cancellable = true)
    private void flightline$suppressAircraftLandingDamage(double fallDistance, float damageMultiplier,
                                                           DamageSource damageSource,
                                                           CallbackInfoReturnable<Boolean> callback) {
        if ((Object) this instanceof ServerPlayer player && FlightController.isFlightActive(player)) {
            callback.setReturnValue(FlightController.handleFallImpact(player, fallDistance));
        }
    }
}

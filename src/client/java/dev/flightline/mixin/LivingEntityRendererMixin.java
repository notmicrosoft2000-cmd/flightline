package dev.flightline.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.flightline.client.BankRenderState;
import dev.flightline.client.FlightLineClient;
import dev.flightline.client.MobBankTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<T extends LivingEntity, S extends LivingEntityRenderState> {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            at = @At("TAIL"))
    private void flightline$extractBank(T entity, S state, float partialTick, CallbackInfo ci) {
        if (entity instanceof Mob mob) {
            float bank = MobBankTracker.bankFor(mob.getId());
            ((BankRenderState) state).flightline$setBank(bank);
            float pitch = MobBankTracker.pitchFor(mob.getId());
            ((BankRenderState) state).flightline$setPitch(pitch);
        } else if (entity instanceof Avatar avatar && avatar == Minecraft.getInstance().player && FlightLineClient.isAirborne()) {
            float bank = FlightLineClient.bankDegrees();
            ((BankRenderState) state).flightline$setBank(bank);
            float pitch = (float) FlightLineClient.pitchDegrees();
            ((BankRenderState) state).flightline$setPitch(pitch);
        }
    }

    @Inject(method = "setupRotations", at = @At("TAIL"))
    private void flightline$applyAttitude(S state, PoseStack poseStack, float bodyYRot, float scale, CallbackInfo ci) {
        float pitch = ((BankRenderState) state).flightline$getPitch();
        if (Math.abs(pitch) > 0.1f) {
            poseStack.rotateDegrees(Axis.XP, -pitch);
        }
        float bank = ((BankRenderState) state).flightline$getBank();
        if (Math.abs(bank) > 0.1f) {
            poseStack.rotateDegrees(Axis.ZP, bank);
        }
    }
}

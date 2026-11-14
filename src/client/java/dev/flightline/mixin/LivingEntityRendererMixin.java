package dev.flightline.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.flightline.client.BankRenderState;
import dev.flightline.client.MobBankTracker;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
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
        }
    }

    @Inject(method = "setupRotations", at = @At("TAIL"))
    private void flightline$applyBank(S state, PoseStack poseStack, float bodyYRot, float scale, CallbackInfo ci) {
        float bank = ((BankRenderState) state).flightline$getBank();
        if (Math.abs(bank) > 0.1f) {
            poseStack.rotateDegrees(Axis.ZP, bank);
        }
    }
}

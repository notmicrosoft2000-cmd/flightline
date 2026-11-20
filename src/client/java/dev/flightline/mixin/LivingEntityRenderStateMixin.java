package dev.flightline.mixin;

import dev.flightline.client.BankRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LivingEntityRenderState.class)
public abstract class LivingEntityRenderStateMixin implements BankRenderState {
    @Unique
    private float flightline$bank;
    @Unique
    private float flightline$pitch;

    @Override
    public float flightline$getBank() {
        return flightline$bank;
    }

    @Override
    public void flightline$setBank(float bank) {
        this.flightline$bank = bank;
    }

    @Override
    public float flightline$getPitch() {
        return flightline$pitch;
    }

    @Override
    public void flightline$setPitch(float pitch) {
        this.flightline$pitch = pitch;
    }
}
